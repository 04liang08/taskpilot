package com.taskpilot.scheduler.service;

import com.taskpilot.common.exception.BizException;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.shard.ShardFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 退避重试：分片执行失败后，按退避间隔在**原执行器**上重试。
 *
 * <p>和失败转移的分工：</p>
 * <ul>
 *   <li>原执行器不可用（挂了/下线了）→ 失败转移换一台机器重试；</li>
 *   <li>原执行器还在 → 失败多半是任务自身的问题（处理器抛异常、依赖抖动）→ 退避后在原地重试。</li>
 * </ul>
 *
 * <p>重试次数受任务的 {@code retry_max} 限制，超过就是终态（死信，等第 4 周做告警）。
 * 退避时间按 {@code retry_interval_seconds} 指数增长，避免把下游打垮。</p>
 *
 * <p>重试状态落在 {@code next_retry_time} 里：内存里的延迟队列只是快路径，
 * 调度中心重启后由定时扫描把到期的重试捞回来。</p>
 *
 * <p><b>线程模型</b>：{@code @Scheduled} 与 retryScheduler 都是单线程的，
 * 真正的执行（同步 HTTP 调用执行器）提交到分片线程池。否则一个耗时任务
 * 就会把调度线程占住，健康检查、僵尸回收等定时任务全部停摆。</p>
 */
@Service
public class RetryService {

    private static final Logger log = LoggerFactory.getLogger(RetryService.class);

    private static final long MAX_BACKOFF_SECONDS = 300;
    private static final int MAX_BACKOFF_SHIFT = 5;

    /**
     * 分片池满了导致重试没发出去时，隔多久再由兜底扫描捞回来。
     */
    private static final long RELEASE_RETRY_DELAY_SECONDS = 5;

    private final TaskService taskService;
    private final ExecutionService executionService;
    private final ExecutorService executorService;
    private final TriggerService triggerService;
    private final ScheduledExecutorService retryScheduler;
    private final ThreadPoolTaskExecutor shardExecutor;

    /** 本地去重：同一条记录同时只安排一个延迟重试。 */
    private final Set<Long> scheduled = ConcurrentHashMap.newKeySet();

    public RetryService(TaskService taskService,
                        ExecutionService executionService,
                        ExecutorService executorService,
                        TriggerService triggerService,
                        @Qualifier("retryScheduler") ScheduledExecutorService retryScheduler,
                        @Qualifier("shardExecutor") ThreadPoolTaskExecutor shardExecutor) {
        this.taskService = taskService;
        this.executionService = executionService;
        this.executorService = executorService;
        this.triggerService = triggerService;
        this.retryScheduler = retryScheduler;
        this.shardExecutor = shardExecutor;
    }

    /**
     * 分片失败：原执行器还在就安排一次退避重试。
     */
    @EventListener
    public void onShardFailed(ShardFailedEvent event) {
        ExecutionLogEntity execution;
        TaskEntity task;
        try {
            execution = executionService.get(event.executionId());
            task = taskService.get(execution.getTaskId());
        } catch (BizException e) {
            return;
        }
        if (!executionService.canRetry(execution, task)) {
            // 没有重试预算了：这条记录就是终态，等第 4 周的死信/告警。
            log.warn("shard failed, no retry budget left: executionId={}, taskId={}, retryCount={}, maxRetry={}",
                    execution.getId(), task.getId(), execution.getRetryCount(), task.getRetryMax());
            return;
        }
        if (!isOnline(execution.getExecutorId())) {
            // 原执行器不可用：交给失败转移换机器，别在这儿耗重试预算。
            return;
        }
        scheduleRetry(execution, task, backoffSeconds(task, execution));
    }

    /**
     * 兜底扫描：调度中心重启后内存里的延迟队列会丢，靠 next_retry_time 把到期的重试捞回来。
     */
    @Scheduled(fixedDelayString = "#{${taskpilot.execution.retry-scan-interval-seconds:10} * 1000}")
    public void scanPendingRetries() {
        try {
            for (ExecutionLogEntity execution : executionService.listPendingRetries()) {
                doRetry(execution.getId());
            }
        } catch (Exception e) {
            log.error("scan pending retries failed", e);
        }
    }

    private void scheduleRetry(ExecutionLogEntity execution, TaskEntity task, long delaySeconds) {
        LocalDateTime nextRetry = LocalDateTime.now().plusSeconds(delaySeconds);
        executionService.markFailedForRetry(execution, "等待退避重试", nextRetry);
        if (!scheduled.add(execution.getId())) {
            return;
        }
        try {
            retryScheduler.schedule(() -> {
                try {
                    doRetry(execution.getId());
                } finally {
                    scheduled.remove(execution.getId());
                }
            }, delaySeconds, TimeUnit.SECONDS);
            log.warn("backoff retry scheduled: executionId={}, taskId={}, retryCount={}/{}, delay={}s",
                    execution.getId(), task.getId(), execution.getRetryCount(), task.getRetryMax(), delaySeconds);
        } catch (RejectedExecutionException e) {
            scheduled.remove(execution.getId());
            // 延迟队列都排不进去了，但记录上的 next_retry_time 已经写好，
            // 兜底扫描照样能把它捞回来，不用在这里额外处理。
            log.error("backoff retry rejected by retry scheduler: executionId={}", execution.getId());
        }
    }

    private void doRetry(Long executionId) {
        ExecutionLogEntity execution;
        TaskEntity task;
        try {
            execution = executionService.get(executionId);
            task = taskService.get(execution.getTaskId());
        } catch (BizException e) {
            return;
        }
        if (!executionService.canRetry(execution, task)) {
            return;
        }
        ExecutorEntity executor = onlineExecutor(execution.getExecutorId());
        if (executor == null) {
            // 原执行器没了：交给失败转移。
            log.info("backoff retry skipped, original executor gone: executionId={}", executionId);
            return;
        }
        if (!executionService.claimForRetry(execution)) {
            // 已被别的路径抢走（失败转移或定时扫描）。
            return;
        }
        log.warn("backoff retry: executionId={}, taskId={}, shard={}/{}, retryCount={}, executor={}",
                execution.getId(), task.getId(), execution.getShardIndex(), execution.getShardTotal(),
                execution.getRetryCount(), executor.getAddress());
        submitDispatch(execution, task, executor);
    }

    /**
     * 真正执行丢到分片线程池：调度线程只负责读库和排队。
     * <p>{@code @Scheduled} 与 retryScheduler 都是单线程的，在这里同步发 HTTP
     * 会把整个调度线程占住，健康检查、僵尸回收都跟着停摆。</p>
     */
    private void submitDispatch(ExecutionLogEntity execution, TaskEntity task, ExecutorEntity executor) {
        try {
            shardExecutor.execute(() ->
                    triggerService.dispatch(execution, task, executor, execution.getTriggerType()));
        } catch (TaskRejectedException e) {
            // 分片池满了：既不能把记录卡在 RUNNING（那就没人再管它了），
            // 也不能直接丢掉这次重试。放回 FAILED 并保留下次重试时间，交给兜底扫描重来。
            executionService.releaseRetryClaim(execution,
                    LocalDateTime.now().plusSeconds(RELEASE_RETRY_DELAY_SECONDS),
                    "分片执行线程池已满，等待重试");
            log.error("backoff retry deferred, shard pool is full: executionId={}", execution.getId());
        }
    }

    /**
     * 指数退避：间隔 × 2^已重试次数，封顶 5 分钟。
     */
    private long backoffSeconds(TaskEntity task, ExecutionLogEntity execution) {
        int base = task.getRetryIntervalSeconds() == null ? 10 : task.getRetryIntervalSeconds();
        int attempt = execution.getRetryCount() == null ? 0 : execution.getRetryCount();
        long delay = base * (1L << Math.min(attempt, MAX_BACKOFF_SHIFT));
        return Math.min(Math.max(delay, 1), MAX_BACKOFF_SECONDS);
    }

    private boolean isOnline(Long executorId) {
        return executorId != null && onlineExecutor(executorId) != null;
    }

    private ExecutorEntity onlineExecutor(Long executorId) {
        if (executorId == null) {
            return null;
        }
        return executorService.listOnline().stream()
                .filter(executor -> executor.getId().equals(executorId))
                .findFirst()
                .orElse(null);
    }
}
