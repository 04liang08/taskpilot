package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.scheduler.client.ExecutorClient;
import com.taskpilot.scheduler.dto.TriggerResult;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.leader.LeaderContext;
import com.taskpilot.scheduler.leader.LeaderStateService;
import com.taskpilot.scheduler.shard.ShardAllocatorRegistry;
import com.taskpilot.scheduler.shard.ShardFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手动触发编排：校验任务 -> 选择在线执行器 -> 落 RUNNING 记录 -> 调用执行器 -> 落终态。
 * <p>第 1 周同步调用、不重试；失败直接记录，重试在第 3 周实现。</p>
 */
@Service
public class TriggerService {

    private static final Logger log = LoggerFactory.getLogger(TriggerService.class);

    private final TaskService taskService;
    private final ExecutorService executorService;
    private final ExecutionService executionService;
    private final ExecutorClient executorClient;
    private final LeaderStateService leaderStateService;
    private final ShardAllocatorRegistry shardAllocatorRegistry;
    private final ThreadPoolTaskExecutor triggerExecutor;
    private final ThreadPoolTaskExecutor shardExecutor;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicInteger roundRobin = new AtomicInteger();
    /**
     * 同一任务不允许并发执行。手动触发与定时触发共用这把锁。
     * <p>只保证单实例内不并发；集群内的重复触发由第 2 周的领导选举兜底。</p>
     */
    private final Set<Long> runningTasks = ConcurrentHashMap.newKeySet();

    public TriggerService(TaskService taskService,
                          ExecutorService executorService,
                          ExecutionService executionService,
                          ExecutorClient executorClient,
                          LeaderStateService leaderStateService,
                          ShardAllocatorRegistry shardAllocatorRegistry,
                          @Qualifier("triggerExecutor") ThreadPoolTaskExecutor triggerExecutor,
                          @Qualifier("shardExecutor") ThreadPoolTaskExecutor shardExecutor,
                          ApplicationEventPublisher eventPublisher) {
        this.taskService = taskService;
        this.executorService = executorService;
        this.executionService = executionService;
        this.executorClient = executorClient;
        this.leaderStateService = leaderStateService;
        this.shardAllocatorRegistry = shardAllocatorRegistry;
        this.triggerExecutor = triggerExecutor;
        this.shardExecutor = shardExecutor;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 定时触发入口：异步执行，时间轮线程只负责发起，不等待结果。
     * <p>leaderContext 携带 fencing token，写库前会校验，旧 leader 的写入会被拒绝。</p>
     */
    public void triggerAsync(Long taskId, LeaderContext leaderContext) {
        try {
            triggerExecutor.execute(() -> {
                try {
                    doTrigger(taskId, TriggerType.CRON, leaderContext);
                } catch (BizException e) {
                    log.warn("scheduled trigger rejected: taskId={}, code={}, message={}",
                            taskId, e.getCode(), e.getMessage());
                } catch (Exception e) {
                    log.error("scheduled trigger failed: taskId={}", taskId, e);
                }
            });
        } catch (TaskRejectedException e) {
            // 触发线程池已满：宁可丢一次触发，也不能阻塞时间轮。
            log.error("scheduled trigger dropped, trigger pool is full: taskId={}", taskId);
        }
    }

    public TriggerResult trigger(Long taskId) {
        return doTrigger(taskId, TriggerType.MANUAL, LeaderContext.none());
    }

    private TriggerResult doTrigger(Long taskId, TriggerType triggerType, LeaderContext leaderContext) {
        if (!runningTasks.add(taskId)) {
            throw new BizException(ErrorCode.CONFLICT, "任务正在执行中，请稍后再试: " + taskId);
        }
        try {
            return executeTrigger(taskId, triggerType, leaderContext);
        } finally {
            runningTasks.remove(taskId);
        }
    }

    private TriggerResult executeTrigger(Long taskId, TriggerType triggerType, LeaderContext leaderContext) {
        TaskEntity task = taskService.get(taskId);
        if (task.getStatus() != TaskStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "任务未启用，无法触发: " + taskId);
        }
        // fencing 校验：锁过期但仍以为自己 leader 的实例，写库时会被这里拒绝。
        if (leaderContext.fenced() && !leaderStateService.checkFence(leaderContext.fencingToken())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "本实例已不是 leader（fencing token 失效），放弃本次触发: taskId=" + taskId);
        }

        List<ExecutorEntity> onlineExecutors = executorService.listOnline();
        if (onlineExecutors.isEmpty()) {
            ExecutionLogEntity failed = executionService.createFailed(task, "没有可用的在线执行器", leaderContext);
            throw new BizException(ErrorCode.EXECUTOR_UNAVAILABLE,
                    "没有可用的在线执行器，执行记录 ID=" + failed.getId() + "（FAILED）");
        }
        taskService.markTriggered(taskId);

        int shardTotal = task.getShardTotal() == null ? 1 : task.getShardTotal();
        if (shardTotal <= 1) {
            ExecutorEntity executor = onlineExecutors.get(
                    Math.floorMod(roundRobin.getAndIncrement(), onlineExecutors.size()));
            ExecutionLogEntity execution = executionService.createRunning(
                    task, executor, triggerType, leaderContext, 0, 1);
            List<ExecutionLogEntity> results = executeShards(
                    List.of(new ShardJob(execution, executor)), task, triggerType);
            return TriggerResult.of(results);
        }

        // 分片任务：一次触发拆成 N 个分片，按策略分配到各个执行器。
        Map<Integer, List<ExecutorEntity>> plan = shardAllocatorRegistry.current()
                .allocate(taskId, shardTotal, onlineExecutors);
        List<ShardJob> jobs = new ArrayList<>();
        for (Map.Entry<Integer, List<ExecutorEntity>> entry : plan.entrySet()) {
            for (ExecutorEntity executor : entry.getValue()) {
                ExecutionLogEntity execution = executionService.createRunning(
                        task, executor, triggerType, leaderContext, entry.getKey(), shardTotal);
                jobs.add(new ShardJob(execution, executor));
            }
        }
        if (jobs.isEmpty()) {
            throw new BizException(ErrorCode.EXECUTOR_UNAVAILABLE,
                    "分片没有分配到任何执行器，taskId=" + taskId);
        }
        List<ExecutionLogEntity> results = executeShards(jobs, task, triggerType);
        log.info("sharded trigger finished: taskId={}, shardTotal={}, executions={}, strategy={}",
                taskId, shardTotal, results.size(), shardAllocatorRegistry.strategy());
        return TriggerResult.of(results);
    }

    /**
     * 并发执行分片：全部落库后一起跑，互不阻塞。
     * <p>用独立的分片线程池：分片之间要并发，且触发线程会等待分片结果，
     * 和触发线程池共用一个池容易把自己饿死。</p>
     */
    private List<ExecutionLogEntity> executeShards(List<ShardJob> jobs,
                                                   TaskEntity task,
                                                   TriggerType triggerType) {
        List<CompletableFuture<ExecutionLogEntity>> futures = new ArrayList<>();
        for (ShardJob job : jobs) {
            futures.add(submitShard(job, task, triggerType));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<ExecutionLogEntity> results = new ArrayList<>();
        for (CompletableFuture<ExecutionLogEntity> future : futures) {
            results.add(future.join());
        }
        return results;
    }

    private CompletableFuture<ExecutionLogEntity> submitShard(ShardJob job,
                                                              TaskEntity task,
                                                              TriggerType triggerType) {
        try {
            return CompletableFuture.supplyAsync(
                    () -> dispatch(job.execution(), task, job.executor(), triggerType), shardExecutor);
        } catch (TaskRejectedException e) {
            // 分片池满了：这个分片直接失败，不影响其他分片。
            return CompletableFuture.completedFuture(
                    executionService.markFailed(job.execution(), "分片执行线程池已满"));
        }
    }

    /**
     * 一个分片：执行记录 + 目标执行器。
     */
    private record ShardJob(ExecutionLogEntity execution, ExecutorEntity executor) {
    }

    /**
     * 调用执行器并落终态。分片与单分片共用这条路径，失败只影响当前分片。
     * <p>失败转移（换执行器重试）也复用这个方法。</p>
     */
    public ExecutionLogEntity dispatch(ExecutionLogEntity execution,
                                       TaskEntity task,
                                       ExecutorEntity executor,
                                       TriggerType triggerType) {
        ExecuteRequest request = new ExecuteRequest(
                execution.getId(),
                task.getId(),
                task.getHandlerName(),
                task.getHandlerParam(),
                execution.getShardIndex(),
                execution.getShardTotal(),
                task.getTimeoutSeconds());

        try {
            ExecuteResult result = executorClient.execute(executor.getAddress(), request);
            ExecutionLogEntity finished = executionService.complete(execution, result);
            log.info("{} finished: taskId={}, executionId={}, shard={}/{}, executor={}, status={}, durationMs={}",
                    triggerType == TriggerType.CRON ? "scheduled trigger" : "manual trigger",
                    task.getId(), execution.getId(), execution.getShardIndex(), execution.getShardTotal(),
                    executor.getAddress(), finished.getStatus(), finished.getDurationMs());
            return finished;
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                ExecutionLogEntity finished = executionService.markTimeout(execution, "调用执行器超时: " + rootMessage(e));
                log.warn("trigger timeout: taskId={}, executionId={}, executor={}, reason={}",
                        task.getId(), execution.getId(), executor.getAddress(), rootMessage(e));
                publishShardFailed(finished);
                return finished;
            }
            ExecutionLogEntity finished = executionService.markFailed(execution, "调用执行器失败: " + rootMessage(e));
            log.warn("trigger failed: taskId={}, executionId={}, executor={}, reason={}",
                    task.getId(), execution.getId(), executor.getAddress(), rootMessage(e));
            publishShardFailed(finished);
            return finished;
        } catch (RestClientException e) {
            ExecutionLogEntity finished = executionService.markFailed(execution, "调用执行器失败: " + rootMessage(e));
            log.warn("trigger failed: taskId={}, executionId={}, executor={}, reason={}",
                    task.getId(), execution.getId(), executor.getAddress(), rootMessage(e));
            publishShardFailed(finished);
            return finished;
        } catch (Exception e) {
            ExecutionLogEntity finished = executionService.markFailed(execution, "触发异常: " + rootMessage(e));
            log.error("trigger error: taskId={}, executionId={}", task.getId(), execution.getId(), e);
            publishShardFailed(finished);
            return finished;
        }
    }

    /**
     * 发布分片失败事件，交给失败转移逻辑判断要不要换执行器重试。
     * <p>只有自动触发（cron）才做失败转移：手动触发失败就是失败，由用户决定要不要重来。</p>
     */
    private void publishShardFailed(ExecutionLogEntity execution) {
        if (execution == null || execution.getTriggerType() != TriggerType.CRON) {
            return;
        }
        eventPublisher.publishEvent(
                new ShardFailedEvent(execution.getId(), execution.getTaskId(), execution.getExecutorId()));
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
