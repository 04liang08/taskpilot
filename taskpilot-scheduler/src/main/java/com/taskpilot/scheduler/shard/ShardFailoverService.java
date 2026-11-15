package com.taskpilot.scheduler.shard;

import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.executor.ExecutorStatusChangedEvent;
import com.taskpilot.scheduler.service.ExecutionService;
import com.taskpilot.scheduler.service.ExecutorService;
import com.taskpilot.scheduler.service.TaskService;
import com.taskpilot.scheduler.service.TriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分片失败转移：执行器不可用时，把卡在它上面的分片重新分配到其他在线执行器并重试。
 *
 * <p>两个触发点：</p>
 * <ol>
 *   <li>分片执行失败（连接被拒、超时等）——如果原执行器已经不可用就立刻转移；</li>
 *   <li>执行器心跳超时下线——扫描它身上所有没结束的分片，逐个转移。</li>
 * </ol>
 *
 * <p>重试<b>复用同一条执行记录</b>（retry_count + 1），而不是新建记录：一次分片的完整生命周期
 * 只有一条记录，能看清它失败过几次、每次的错误。幂等靠 execution_key 不变 +
 * {@code claimForRetry} 的原子抢占保证同一个分片不会被执行两遍。</p>
 */
@Component
public class ShardFailoverService {

    private static final Logger log = LoggerFactory.getLogger(ShardFailoverService.class);

    private final TaskService taskService;
    private final ExecutorService executorService;
    private final ExecutionService executionService;
    private final TriggerService triggerService;
    private final ShardAllocatorRegistry shardAllocatorRegistry;
    private final ThreadPoolTaskExecutor shardExecutor;

    /** 本地幂等：同一个执行记录同时只允许一个转移在跑。 */
    private final Set<Long> transferring = ConcurrentHashMap.newKeySet();

    public ShardFailoverService(TaskService taskService,
                                ExecutorService executorService,
                                ExecutionService executionService,
                                TriggerService triggerService,
                                ShardAllocatorRegistry shardAllocatorRegistry,
                                @Qualifier("shardExecutor") ThreadPoolTaskExecutor shardExecutor) {
        this.taskService = taskService;
        this.executorService = executorService;
        this.executionService = executionService;
        this.triggerService = triggerService;
        this.shardAllocatorRegistry = shardAllocatorRegistry;
        this.shardExecutor = shardExecutor;
    }

    /**
     * 分片执行失败：原执行器已经不可用就立刻转移到别的执行器。
     */
    @EventListener
    public void onShardFailed(ShardFailedEvent event) {
        try {
            ExecutionLogEntity execution = executionService.get(event.executionId());
            submit(execution);
        } catch (BizException e) {
            log.warn("shard failed event skipped, record missing: executionId={}", event.executionId());
        }
    }

    /**
     * 执行器下线：把它身上所有没结束的分片转移到其他在线执行器。
     * <p>顺序在 {@code ExecutorStatusListener} 之后：先收尾（RUNNING → FAILED），
     * 这里才能用一条 UPDATE 把它原子抢过来重试。</p>
     */
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onExecutorStatusChanged(ExecutorStatusChangedEvent event) {
        if (event.online()) {
            return;
        }
        List<ExecutionLogEntity> unfinished = executionService.listUnfinishedOfExecutor(event.executorId());
        if (unfinished.isEmpty()) {
            return;
        }
        log.warn("executor offline, failover candidates: executor={}, address={}, shards={}",
                event.executorId(), event.address(), unfinished.size());
        for (ExecutionLogEntity execution : unfinished) {
            submit(execution);
        }
    }

    private void submit(ExecutionLogEntity execution) {
        try {
            shardExecutor.execute(() -> failover(execution));
        } catch (TaskRejectedException e) {
            log.error("shard failover dropped, shard pool is full: executionId={}", execution.getId());
        }
    }

    void failover(ExecutionLogEntity execution) {
        if (!transferring.add(execution.getId())) {
            return;
        }
        try {
            TaskEntity task = taskService.get(execution.getTaskId());
            if (task == null || task.getStatus() != TaskStatus.ENABLED) {
                // 任务没了/停用了：没有重试的可能，落终态，别让兜底扫描反复捞回来。
                executionService.markRetryExhausted(execution);
                return;
            }
            if (execution.getStatus() == ExecutionStatus.RUNNING) {
                // 收尾（ExecutorStatusListener）应该已经先把它变成 FAILED 了。
                // 万一顺序没生效，这里抢不动 RUNNING 的记录：手动收尾并挂上重试时间，
                // 交给退避扫描兜底。绝不能让它停在 RUNNING——那样它再也不会被处理。
                executionService.markRunningForRetry(execution.getId(), "执行器下线: 等待重试");
                log.warn("shard failover deferred, record still running: executionId={}", execution.getId());
                return;
            }
            if (execution.getExecutorId() != null && isOnline(execution.getExecutorId())) {
                // 原执行器还在线：失败多半是任务自身的问题（处理器抛异常），不该转移。
                log.info("shard failover skipped, executor still online: executionId={}, executorId={}",
                        execution.getId(), execution.getExecutorId());
                return;
            }
            if (!executionService.canRetry(execution, task)) {
                // 重试预算用完：清掉 next_retry_time，这条记录当场落终态（死信），
                // 不再换机重试，也不会被兜底扫描反复捞回来。
                log.warn("shard failover skipped, no retry budget left: executionId={}, retryCount={}, maxRetry={}",
                        execution.getId(), execution.getRetryCount(), task.getRetryMax());
                executionService.markRetryExhausted(execution);
                return;
            }
            List<ExecutorEntity> candidates = executorService.listOnline().stream()
                    .filter(executor -> !executor.getId().equals(execution.getExecutorId()))
                    .toList();
            if (candidates.isEmpty()) {
                log.warn("shard failover skipped, no other online executor: executionId={}", execution.getId());
                return;
            }
            Map<Integer, List<ExecutorEntity>> plan = shardAllocatorRegistry.current()
                    .allocate(task.getId(), execution.getShardTotal(), candidates);
            List<ExecutorEntity> targets = plan.get(execution.getShardIndex());
            if (targets == null || targets.isEmpty()) {
                log.warn("shard failover skipped, shard not allocated: executionId={}, shard={}",
                        execution.getId(), execution.getShardIndex());
                return;
            }
            ExecutorEntity target = targets.get(0);
            String previousAddress = execution.getExecutorAddress();
            if (!executionService.claimForRetry(execution, target)) {
                // 别的实例已经抢到这次重试。
                log.info("shard failover skipped, already claimed: executionId={}", execution.getId());
                return;
            }
            log.warn("shard failover: executionId={}, taskId={}, shard={}/{}, {} -> {}, retryCount={}",
                    execution.getId(), task.getId(), execution.getShardIndex(), execution.getShardTotal(),
                    previousAddress, target.getAddress(), execution.getRetryCount());
            triggerService.dispatch(execution, task, target, execution.getTriggerType());
        } catch (BizException e) {
            log.warn("shard failover aborted: executionId={}, message={}", execution.getId(), e.getMessage());
        } catch (Exception e) {
            log.error("shard failover failed: executionId={}", execution.getId(), e);
        } finally {
            transferring.remove(execution.getId());
        }
    }

    private boolean isOnline(Long executorId) {
        return executorService.listOnline().stream()
                .anyMatch(executor -> executor.getId().equals(executorId));
    }
}
