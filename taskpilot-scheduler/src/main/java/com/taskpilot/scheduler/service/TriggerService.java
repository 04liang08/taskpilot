package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.ExecuteAck;
import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.common.metrics.MetricNames;
import com.taskpilot.scheduler.client.ExecutorClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.taskpilot.scheduler.dto.CreatedExecution;
import com.taskpilot.scheduler.dto.TriggerResult;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.leader.LeaderContext;
import com.taskpilot.scheduler.leader.LeaderStateService;
import com.taskpilot.scheduler.shard.ShardAllocatorRegistry;
import com.taskpilot.scheduler.support.ExecutionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

/**
 * 手动触发编排：校验任务 -> 选择在线执行器 -> 落 RUNNING 记录 -> 调用执行器 -> 落终态。
 * <p>第 4 周起派发变成"快回包"：执行器立即回 ACK，结果经 RabbitMQ 异步回传，
 * 终态由结果消费者落（见 {@link ResultProcessor}）。</p>
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
    private final ResultProcessor resultProcessor;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger roundRobin = new AtomicInteger();

    public TriggerService(TaskService taskService,
                          ExecutorService executorService,
                          ExecutionService executionService,
                          ExecutorClient executorClient,
                          LeaderStateService leaderStateService,
                          ShardAllocatorRegistry shardAllocatorRegistry,
                          @Qualifier("triggerExecutor") ThreadPoolTaskExecutor triggerExecutor,
                          @Qualifier("shardExecutor") ThreadPoolTaskExecutor shardExecutor,
                          ResultProcessor resultProcessor,
                          MeterRegistry meterRegistry) {
        this.taskService = taskService;
        this.executorService = executorService;
        this.executionService = executionService;
        this.executorClient = executorClient;
        this.leaderStateService = leaderStateService;
        this.shardAllocatorRegistry = shardAllocatorRegistry;
        this.triggerExecutor = triggerExecutor;
        this.shardExecutor = shardExecutor;
        this.resultProcessor = resultProcessor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 定时触发入口：异步执行，时间轮线程只负责发起，不等待结果。
     * <p>leaderContext 携带 fencing token，写库前会校验，旧 leader 的写入会被拒绝。</p>
     * <p>scheduledTime 是<b>计划触发时间</b>，用它算确定性幂等键：同一个计划时间点的
     * 重复触发会撞唯一索引，不会产生第二条执行记录。</p>
     */
    public void triggerAsync(Long taskId, LeaderContext leaderContext, LocalDateTime scheduledTime) {
        try {
            triggerExecutor.execute(() -> {
                try {
                    doTrigger(taskId, TriggerType.CRON, leaderContext, scheduledTime);
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
        return doTrigger(taskId, TriggerType.MANUAL, LeaderContext.none(), null);
    }

    private TriggerResult doTrigger(Long taskId,
                                    TriggerType triggerType,
                                    LeaderContext leaderContext,
                                    LocalDateTime scheduledTime) {
        return executeTrigger(taskId, triggerType, leaderContext, scheduledTime);
    }

    private TriggerResult executeTrigger(Long taskId,
                                         TriggerType triggerType,
                                         LeaderContext leaderContext,
                                         LocalDateTime scheduledTime) {
        TaskEntity task = taskService.get(taskId);
        if (task.getStatus() != TaskStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "任务未启用，无法触发: " + taskId);
        }
        // 同一任务不允许并发执行。
        //
        // 第 4 周起这里查 DB 而不是查内存锁：触发变成异步之后（结果经 RabbitMQ 回传），
        // doTrigger 秒回、进程内的锁立刻释放，防并发就失效了；而且集群里别的实例
        // 根本看不到那把锁。查 DB 才是集群口径。
        //
        // 已知竞态：check 与 insert 之间还有毫秒级窗口，两个实例同时通过检查时
        // 仍可能各建一条记录。重复落库由幂等键 + uk_execution_key 兜住（ADR-017）。
        if (executionService.existsRunning(taskId)) {
            countTrigger(triggerType, "conflict_running");
            throw new BizException(ErrorCode.CONFLICT, "任务正在执行中，请稍后再试: " + taskId);
        }
        // fencing 校验：锁过期但仍以为自己 leader 的实例，写库时会被这里拒绝。
        if (leaderContext.fenced() && !leaderStateService.checkFence(leaderContext.fencingToken())) {
            countTrigger(triggerType, "fencing_rejected");
            throw new BizException(ErrorCode.CONFLICT,
                    "本实例已不是 leader（fencing token 失效），放弃本次触发: taskId=" + taskId);
        }

        List<ExecutorEntity> onlineExecutors = executorService.listOnline();
        if (onlineExecutors.isEmpty()) {
            countTrigger(triggerType, "no_executor");
            ExecutionLogEntity failed = executionService.createFailed(
                    task, "没有可用的在线执行器", leaderContext, triggerType);
            throw new BizException(ErrorCode.EXECUTOR_UNAVAILABLE,
                    "没有可用的在线执行器，执行记录 ID=" + failed.getId() + "（FAILED）");
        }
        taskService.markTriggered(taskId);

        int shardTotal = task.getShardTotal() == null ? 1 : task.getShardTotal();
        if (shardTotal <= 1) {
            ExecutorEntity executor = onlineExecutors.get(
                    Math.floorMod(roundRobin.getAndIncrement(), onlineExecutors.size()));
            List<ExecutionLogEntity> results = executeShards(
                    List.of(new ShardJob(task, executor, 0, 1, executionKey(taskId, scheduledTime, 0, executor, 1))),
                    task, triggerType, leaderContext);
            countTrigger(triggerType, "dispatched");
            return TriggerResult.of(results);
        }

        // 分片任务：一次触发拆成 N 个分片，按策略分配到各个执行器。
        Map<Integer, List<ExecutorEntity>> plan = shardAllocatorRegistry.current()
                .allocate(taskId, shardTotal, onlineExecutors);
        List<ShardJob> jobs = new ArrayList<>();
        for (Map.Entry<Integer, List<ExecutorEntity>> entry : plan.entrySet()) {
            List<ExecutorEntity> targets = entry.getValue();
            for (ExecutorEntity executor : targets) {
                jobs.add(new ShardJob(task, executor, entry.getKey(), shardTotal,
                        executionKey(taskId, scheduledTime, entry.getKey(), executor, targets.size())));
            }
        }
        if (jobs.isEmpty()) {
            countTrigger(triggerType, "no_executor");
            throw new BizException(ErrorCode.EXECUTOR_UNAVAILABLE,
                    "分片没有分配到任何执行器，taskId=" + taskId);
        }
        List<ExecutionLogEntity> results = executeShards(jobs, task, triggerType, leaderContext);
        log.info("sharded trigger finished: taskId={}, shardTotal={}, executions={}, strategy={}",
                taskId, shardTotal, results.size(), shardAllocatorRegistry.strategy());
        countTrigger(triggerType, "dispatched");
        return TriggerResult.of(results);
    }

    /**
     * 幂等键：定时触发用确定性键（同一计划时间点只会落一条记录），
     * 手动触发用 UUID（允许重复触发）。
     * <p>一个分片有多个目标执行器时（广播策略）键要带上执行器维度：
     * 否则第二台执行器会撞唯一索引被当成"重复触发"跳过，广播实际只剩一台在跑。</p>
     * <p>单目标分片的键保持不变，不影响已有的去重语义。</p>
     */
    private String executionKey(Long taskId,
                                LocalDateTime scheduledTime,
                                int shardIndex,
                                ExecutorEntity executor,
                                int targetCount) {
        if (scheduledTime == null) {
            return UUID.randomUUID().toString();
        }
        if (targetCount > 1 && executor != null && executor.getId() != null) {
            return ExecutionKeys.deterministic(taskId, scheduledTime, shardIndex, executor.getId());
        }
        return ExecutionKeys.deterministic(taskId, scheduledTime, shardIndex);
    }

    /**
     * 并发执行分片：全部落库后一起跑，互不阻塞。
     * <p>用独立的分片线程池：分片之间要并发，且触发线程会等待分片结果，
     * 和触发线程池共用一个池容易把自己饿死。</p>
     */
    private List<ExecutionLogEntity> executeShards(List<ShardJob> jobs,
                                                   TaskEntity task,
                                                   TriggerType triggerType,
                                                   LeaderContext leaderContext) {
        List<CompletableFuture<ExecutionLogEntity>> futures = new ArrayList<>();
        for (ShardJob job : jobs) {
            futures.add(submitShard(job, task, triggerType, leaderContext));
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
                                                              TriggerType triggerType,
                                                              LeaderContext leaderContext) {
        CreatedExecution created = executionService.createRunning(job.task(), job.executor(), triggerType,
                leaderContext, job.shardIndex(), job.shardTotal(), job.executionKey());
        if (!created.created()) {
            // 幂等键已存在：同一个计划时间点的重复触发，不重复执行。
            log.info("duplicate trigger skipped: taskId={}, shard={}/{}, executionKey={}",
                    task.getId(), job.shardIndex(), job.shardTotal(), job.executionKey());
            return CompletableFuture.completedFuture(created.entity());
        }
        try {
            return CompletableFuture.supplyAsync(
                    () -> dispatch(created.entity(), task, job.executor(), triggerType), shardExecutor);
        } catch (TaskRejectedException e) {
            // 分片池满了：这个分片直接失败，不影响其他分片。
            return CompletableFuture.completedFuture(
                    resultProcessor.processDispatchFailure(created.entity(), ExecutionStatus.FAILED,
                            "分片执行线程池已满"));
        }
    }

    /**
     * 一个分片：任务、目标执行器、分片号与幂等键。
     */
    private record ShardJob(TaskEntity task,
                            ExecutorEntity executor,
                            int shardIndex,
                            int shardTotal,
                            String executionKey) {
    }

    /**
     * 调用执行器并落终态。
     *
     * <p>第 4 周起这里只做"派发"：执行器立即回 ACK（受理与否），
     * 真正的执行结果由执行器经 RabbitMQ 回传，这里不等待。
     * 所以：</p>
     * <ul>
     *   <li>受理成功 → 记录保持 RUNNING，等结果消息来收尾；</li>
     *   <li>受理失败 / 连不上 / ACK 超时 → 调度侧自己把记录收成终态并发失败事件。</li>
     * </ul>
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

        long dispatchStartNanos = System.nanoTime();
        try {
            ExecuteAck ack = executorClient.execute(executor.getAddress(), request);
            if (ack.accepted()) {
                // 执行器已受理：结果稍后经 RabbitMQ 回传，记录保持 RUNNING。
                recordDispatch(dispatchStartNanos, "accepted");
                log.info("{} dispatched: taskId={}, executionId={}, shard={}/{}, executor={}",
                        triggerType == TriggerType.CRON ? "scheduled trigger" : "manual trigger",
                        task.getId(), execution.getId(), execution.getShardIndex(), execution.getShardTotal(),
                        executor.getAddress());
                return execution;
            }
            // 执行器现在接不了（线程池满等）：直接失败，走原来的重试 / 失败转移链路。
            recordDispatch(dispatchStartNanos, "rejected");
            log.warn("{} rejected by executor: taskId={}, executionId={}, reason={}",
                    triggerType == TriggerType.CRON ? "scheduled trigger" : "manual trigger",
                    task.getId(), execution.getId(), ack.rejectReason());
            return resultProcessor.processDispatchFailure(execution, ExecutionStatus.FAILED,
                    "执行器拒绝执行: " + ack.rejectReason());
        } catch (ResourceAccessException e) {
            recordDispatch(dispatchStartNanos, "error");
            if (isTimeout(e)) {
                log.warn("dispatch timeout: taskId={}, executionId={}, executor={}, reason={}",
                        task.getId(), execution.getId(), executor.getAddress(), rootMessage(e));
                return resultProcessor.processDispatchFailure(execution, ExecutionStatus.TIMEOUT,
                        "调用执行器超时: " + rootMessage(e));
            }
            log.warn("dispatch failed: taskId={}, executionId={}, executor={}, reason={}",
                    task.getId(), execution.getId(), executor.getAddress(), rootMessage(e));
            return resultProcessor.processDispatchFailure(execution, ExecutionStatus.FAILED,
                    "调用执行器失败: " + rootMessage(e));
        } catch (RestClientException e) {
            recordDispatch(dispatchStartNanos, "error");
            log.warn("dispatch failed: taskId={}, executionId={}, executor={}, reason={}",
                    task.getId(), execution.getId(), executor.getAddress(), rootMessage(e));
            return resultProcessor.processDispatchFailure(execution, ExecutionStatus.FAILED,
                    "调用执行器失败: " + rootMessage(e));
        } catch (Exception e) {
            recordDispatch(dispatchStartNanos, "error");
            log.error("dispatch error: taskId={}, executionId={}", task.getId(), execution.getId(), e);
            return resultProcessor.processDispatchFailure(execution, ExecutionStatus.FAILED,
                    "触发异常: " + rootMessage(e));
        }
    }

    /**
     * 触发计数（ADR-022）：outcome 是低基数枚举——
     * dispatched=通过全部守卫进入派发；conflict_running/fencing_rejected/no_executor 对应三个守卫拒绝点。
     */
    private void countTrigger(TriggerType triggerType, String outcome) {
        Counter.builder(MetricNames.TRIGGERS)
                .description("触发次数（按类型与结局）")
                .tag("trigger_type", triggerType == TriggerType.CRON ? "cron" : "manual")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /** 派发 ACK 往返耗时（accepted|rejected|error）。 */
    private void recordDispatch(long startNanos, String outcome) {
        Timer.builder(MetricNames.DISPATCH)
                .description("派发请求的执行器 ACK 往返耗时")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    /**
     * 判断是不是读超时。
     * <p>注意：第 4 周起"任务超时"由执行器判定并上报，这里的超时只指
     * "连执行器的 ACK 都没拿到"（网络/进程层面）。</p>
     */
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
