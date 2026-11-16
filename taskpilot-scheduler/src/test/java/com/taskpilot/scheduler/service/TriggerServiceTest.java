package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.ExecuteAck;
import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.scheduler.client.ExecutorClient;
import com.taskpilot.scheduler.dto.CreatedExecution;
import com.taskpilot.scheduler.dto.TriggerResult;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.leader.LeaderContext;
import com.taskpilot.scheduler.leader.LeaderStateService;
import com.taskpilot.scheduler.shard.ShardAllocator;
import com.taskpilot.scheduler.shard.ShardAllocatorRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriggerServiceTest {

    /** 固定的"计划触发时间"，用来验证确定性幂等键的格式。 */
    private static final LocalDateTime SCHEDULED_TIME = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    @Mock
    private TaskService taskService;

    @Mock
    private ExecutorService executorService;

    @Mock
    private ExecutionService executionService;

    @Mock
    private ExecutorClient executorClient;

    @Mock
    private LeaderStateService leaderStateService;

    @Mock
    private ShardAllocatorRegistry shardAllocatorRegistry;

    @Mock
    private ShardAllocator shardAllocator;

    @Mock
    private ThreadPoolTaskExecutor triggerExecutor;

    @Mock
    private ResultProcessor resultProcessor;

    private ThreadPoolTaskExecutor shardExecutor;
    private TriggerService triggerService;

    @BeforeEach
    void setUp() {
        // 分片执行用的是真实线程池：CompletableFuture 依赖 executor 真正把任务跑起来。
        shardExecutor = new ThreadPoolTaskExecutor();
        shardExecutor.setCorePoolSize(4);
        shardExecutor.setMaxPoolSize(8);
        shardExecutor.setQueueCapacity(64);
        shardExecutor.initialize();
        triggerService = new TriggerService(taskService, executorService, executionService, executorClient,
                leaderStateService, shardAllocatorRegistry, triggerExecutor, shardExecutor, resultProcessor);
    }

    @AfterEach
    void tearDown() {
        shardExecutor.shutdown();
    }

    @Test
    void triggerDispatchesToExecutorAndKeepsRecordRunning() {
        // 第 4 周起：执行器只回 ACK，结果经 MQ 回传，接口返回时记录还是 RUNNING。
        TaskEntity task = enabledTask();
        ExecutorEntity executor = onlineExecutor();
        ExecutionLogEntity running = runningExecution(200L);
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(executor));
        when(executionService.createRunning(eq(task), eq(executor), eq(TriggerType.MANUAL), any(), anyInt(), anyInt(),
                anyString())).thenReturn(new CreatedExecution(running, true));
        when(executorClient.execute(eq("127.0.0.1:8081"), any(ExecuteRequest.class)))
                .thenReturn(ExecuteAck.ok());

        TriggerResult result = triggerService.trigger(1L);

        assertThat(result.executionId()).isEqualTo(200L);
        assertThat(result.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(result.executions()).containsExactly(running);
        ArgumentCaptor<ExecuteRequest> requestCaptor = ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(executorClient).execute(eq("127.0.0.1:8081"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().executionId()).isEqualTo(200L);
        assertThat(requestCaptor.getValue().handlerName()).isEqualTo("demoHandler");
        assertThat(requestCaptor.getValue().timeoutSeconds()).isEqualTo(30);
        // 受理成功不该走"调度侧失败"路径。
        verify(resultProcessor, never()).processDispatchFailure(any(), any(), anyString());
    }

    @Test
    void triggerRejectsDisabledTask() {
        TaskEntity task = enabledTask();
        task.setStatus(TaskStatus.DISABLED);
        when(taskService.get(1L)).thenReturn(task);

        assertThatThrownBy(() -> triggerService.trigger(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("任务未启用");

        verifyNoInteractions(executorService, executionService, executorClient);
    }

    @Test
    void triggerWithoutOnlineExecutorRecordsFailedAndThrows() {
        TaskEntity task = enabledTask();
        ExecutionLogEntity failed = runningExecution(201L);
        failed.setStatus(ExecutionStatus.FAILED);
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of());
        when(executionService.createFailed(eq(task), anyString(), any(), eq(TriggerType.MANUAL)))
                .thenReturn(failed);

        assertThatThrownBy(() -> triggerService.trigger(1L))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getCode())
                        .isEqualTo(ErrorCode.EXECUTOR_UNAVAILABLE.getCode()))
                .hasMessageContaining("执行记录 ID=201");

        verify(executionService).createFailed(eq(task), eq("没有可用的在线执行器"), any(),
                eq(TriggerType.MANUAL));
        verify(executorClient, never()).execute(anyString(), any(ExecuteRequest.class));
    }

    @Test
    void scheduledTriggerWithoutOnlineExecutorRecordsCronFailure() {
        // cron 触发时没有在线执行器：FAILED 记录必须标 CRON，不能被误记成 MANUAL。
        TaskEntity task = enabledTask();
        ExecutionLogEntity failed = runningExecution(205L);
        failed.setStatus(ExecutionStatus.FAILED);
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of());
        when(executionService.createFailed(eq(task), anyString(), any(), eq(TriggerType.CRON)))
                .thenReturn(failed);
        runTriggerInline();

        triggerService.triggerAsync(1L, LeaderContext.none(), SCHEDULED_TIME);

        verify(executionService).createFailed(eq(task), eq("没有可用的在线执行器"), any(),
                eq(TriggerType.CRON));
        verify(executorClient, never()).execute(anyString(), any(ExecuteRequest.class));
    }

    @Test
    void rejectedByExecutorBecomesFailed() {
        // 执行器接不了（线程池满等）：调度侧自己把记录收成 FAILED，走原来的重试链路。
        TaskEntity task = enabledTask();
        ExecutorEntity executor = onlineExecutor();
        ExecutionLogEntity running = runningExecution(202L);
        ExecutionLogEntity failed = runningExecution(202L);
        failed.setStatus(ExecutionStatus.FAILED);
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(executor));
        when(executionService.createRunning(eq(task), eq(executor), eq(TriggerType.MANUAL), any(), anyInt(), anyInt(),
                anyString())).thenReturn(new CreatedExecution(running, true));
        when(executorClient.execute(eq("127.0.0.1:8081"), any(ExecuteRequest.class)))
                .thenReturn(ExecuteAck.rejected("执行器调度队列已满"));
        when(resultProcessor.processDispatchFailure(eq(running), eq(ExecutionStatus.FAILED), anyString()))
                .thenReturn(failed);

        TriggerResult result = triggerService.trigger(1L);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.executions()).containsExactly(failed);
        verify(resultProcessor).processDispatchFailure(eq(running), eq(ExecutionStatus.FAILED),
                contains("执行器调度队列已满"));
    }

    @Test
    void triggerMarksFailedWhenExecutorConnectionFails() {
        TaskEntity task = enabledTask();
        ExecutorEntity executor = onlineExecutor();
        ExecutionLogEntity running = runningExecution(202L);
        ExecutionLogEntity failed = runningExecution(202L);
        failed.setStatus(ExecutionStatus.FAILED);
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(executor));
        when(executionService.createRunning(eq(task), eq(executor), eq(TriggerType.MANUAL), any(), anyInt(), anyInt(),
                anyString())).thenReturn(new CreatedExecution(running, true));
        when(executorClient.execute(eq("127.0.0.1:8081"), any(ExecuteRequest.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));
        when(resultProcessor.processDispatchFailure(eq(running), eq(ExecutionStatus.FAILED), anyString()))
                .thenReturn(failed);

        TriggerResult result = triggerService.trigger(1L);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.executions()).containsExactly(failed);
        verify(resultProcessor).processDispatchFailure(eq(running), eq(ExecutionStatus.FAILED), anyString());
        verify(resultProcessor, never()).processDispatchFailure(any(), eq(ExecutionStatus.TIMEOUT), anyString());
    }

    @Test
    void ackTimeoutBecomesTimeout() {
        // 连 ACK 都没拿到（网络/进程层面）：算超时，不是任务超时。
        TaskEntity task = enabledTask();
        ExecutorEntity executor = onlineExecutor();
        ExecutionLogEntity running = runningExecution(206L);
        ExecutionLogEntity timedOut = runningExecution(206L);
        timedOut.setStatus(ExecutionStatus.TIMEOUT);
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(executor));
        when(executionService.createRunning(eq(task), eq(executor), eq(TriggerType.MANUAL), any(), anyInt(), anyInt(),
                anyString())).thenReturn(new CreatedExecution(running, true));
        when(executorClient.execute(eq("127.0.0.1:8081"), any(ExecuteRequest.class)))
                .thenThrow(new ResourceAccessException("read timed out", new SocketTimeoutException("Read timed out")));
        when(resultProcessor.processDispatchFailure(eq(running), eq(ExecutionStatus.TIMEOUT), anyString()))
                .thenReturn(timedOut);

        TriggerResult result = triggerService.trigger(1L);

        assertThat(result.status()).isEqualTo(ExecutionStatus.TIMEOUT);
        verify(resultProcessor).processDispatchFailure(eq(running), eq(ExecutionStatus.TIMEOUT), anyString());
    }

    @Test
    void concurrentTriggerOfSameTaskIsRejected() {
        // 第 4 周起触发是异步的，进程内的内存锁失效：改成查 DB 有没有 RUNNING 记录。
        // 这才是集群口径——别的实例跑着的任务，这边也要拒。
        TaskEntity task = enabledTask();
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.existsRunning(1L)).thenReturn(true);

        assertThatThrownBy(() -> triggerService.trigger(1L))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getCode())
                        .isEqualTo(ErrorCode.CONFLICT.getCode()))
                .hasMessageContaining("正在执行中");

        verify(executorClient, never()).execute(anyString(), any(ExecuteRequest.class));
    }

    @Test
    void shardedTaskCreatesOneExecutionPerShard() {
        TaskEntity task = enabledTask();
        task.setShardTotal(3);
        ExecutorEntity first = onlineExecutor();
        ExecutorEntity second = onlineExecutor();
        second.setId(11L);
        second.setAddress("127.0.0.1:8082");

        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        plan.put(0, List.of(first));
        plan.put(1, List.of(second));
        plan.put(2, List.of(first));

        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(first, second));
        when(shardAllocatorRegistry.current()).thenReturn(shardAllocator);
        when(shardAllocator.allocate(eq(1L), eq(3), anyList())).thenReturn(plan);

        AtomicLong sequence = new AtomicLong(300L);
        when(executionService.createRunning(eq(task), any(ExecutorEntity.class), eq(TriggerType.MANUAL),
                any(), anyInt(), eq(3), anyString())).thenAnswer(invocation -> {
            int shardIndex = invocation.getArgument(4);
            ExecutionLogEntity execution = runningExecution(sequence.incrementAndGet());
            execution.setShardIndex(shardIndex);
            execution.setShardTotal(3);
            return new CreatedExecution(execution, true);
        });
        when(executorClient.execute(anyString(), any(ExecuteRequest.class))).thenReturn(ExecuteAck.ok());

        TriggerResult result = triggerService.trigger(1L);

        assertThat(result.shardTotal()).isEqualTo(3);
        assertThat(result.executions()).hasSize(3);
        assertThat(result.executions()).extracting(ExecutionLogEntity::getShardIndex)
                .containsExactly(0, 1, 2);
        // 分片是并发执行的，调用顺序不固定，按集合断言。
        ArgumentCaptor<ExecuteRequest> captor = ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(executorClient, times(3)).execute(anyString(), captor.capture());
        assertThat(captor.getAllValues()).extracting(ExecuteRequest::shardIndex)
                .containsExactlyInAnyOrder(0, 1, 2);
        assertThat(captor.getAllValues()).extracting(ExecuteRequest::shardTotal)
                .containsOnly(3);
    }

    @Test
    void shardsRunInParallel() throws Exception {
        // 两个分片都要等对方进入：串行执行会互相卡死，只有并发才能一起通过。
        CountDownLatch entered = new CountDownLatch(2);
        TaskEntity task = enabledTask();
        task.setShardTotal(2);
        ExecutorEntity first = onlineExecutor();
        ExecutorEntity second = onlineExecutor();
        second.setId(11L);
        second.setAddress("127.0.0.1:8082");

        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        plan.put(0, List.of(first));
        plan.put(1, List.of(second));

        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(first, second));
        when(shardAllocatorRegistry.current()).thenReturn(shardAllocator);
        when(shardAllocator.allocate(eq(1L), eq(2), anyList())).thenReturn(plan);
        when(executionService.createRunning(eq(task), any(ExecutorEntity.class), eq(TriggerType.MANUAL),
                any(), anyInt(), eq(2), anyString())).thenAnswer(invocation -> {
            int shardIndex = invocation.getArgument(4);
            ExecutionLogEntity execution = runningExecution(400L + shardIndex);
            execution.setShardIndex(shardIndex);
            execution.setShardTotal(2);
            return new CreatedExecution(execution, true);
        });
        when(executorClient.execute(anyString(), any(ExecuteRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            return ExecuteAck.ok();
        });

        TriggerResult result = triggerService.trigger(1L);

        assertThat(result.executions()).hasSize(2);
        // 结果还没回来，汇总状态必须是 RUNNING，不能当成 SUCCESS。
        assertThat(result.status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void broadcastShardCreatesOneExecutionPerExecutor() {
        // 广播：每个分片都投给所有执行器，幂等键必须带上执行器维度，
        // 否则第二台起就撞唯一索引被当成重复触发跳过，广播实际只剩一台在跑。
        TaskEntity task = enabledTask();
        task.setShardTotal(2);
        ExecutorEntity first = onlineExecutor();
        ExecutorEntity second = onlineExecutor();
        second.setId(11L);
        second.setAddress("127.0.0.1:8082");

        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        plan.put(0, List.of(first, second));
        plan.put(1, List.of(first, second));

        List<String> keys = Collections.synchronizedList(new ArrayList<>());
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(first, second));
        when(shardAllocatorRegistry.current()).thenReturn(shardAllocator);
        when(shardAllocator.allocate(eq(1L), eq(2), anyList())).thenReturn(plan);
        when(executionService.createRunning(eq(task), any(ExecutorEntity.class), eq(TriggerType.CRON),
                any(), anyInt(), eq(2), anyString())).thenAnswer(invocation -> {
            keys.add(invocation.getArgument(6));
            ExecutionLogEntity execution = runningExecution(500L + keys.size());
            execution.setShardIndex(invocation.getArgument(4));
            execution.setShardTotal(2);
            return new CreatedExecution(execution, true);
        });
        when(executorClient.execute(anyString(), any(ExecuteRequest.class))).thenReturn(ExecuteAck.ok());
        runTriggerInline();

        triggerService.triggerAsync(1L, LeaderContext.none(), SCHEDULED_TIME);

        // 2 个分片 × 2 台执行器 = 4 条记录、4 次调用，4 个互不相同的幂等键。
        assertThat(keys).hasSize(4).doesNotHaveDuplicates();
        verify(executorClient, times(4)).execute(anyString(), any(ExecuteRequest.class));
        assertThat(keys).allMatch(key -> key.startsWith("1:20260101100000:"));
        assertThat(keys).allMatch(key -> key.split(":").length == 4);
    }

    @Test
    void singleTargetShardKeepsKeyWithoutExecutorDimension() {
        // 单目标分片的键保持不变，不能因为广播的修复把普通分片的去重语义改掉。
        TaskEntity task = enabledTask();
        task.setShardTotal(2);
        ExecutorEntity first = onlineExecutor();
        ExecutorEntity second = onlineExecutor();
        second.setId(11L);
        second.setAddress("127.0.0.1:8082");

        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        plan.put(0, List.of(first));
        plan.put(1, List.of(second));

        List<String> keys = Collections.synchronizedList(new ArrayList<>());
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(first, second));
        when(shardAllocatorRegistry.current()).thenReturn(shardAllocator);
        when(shardAllocator.allocate(eq(1L), eq(2), anyList())).thenReturn(plan);
        when(executionService.createRunning(eq(task), any(ExecutorEntity.class), eq(TriggerType.CRON),
                any(), anyInt(), eq(2), anyString())).thenAnswer(invocation -> {
            keys.add(invocation.getArgument(6));
            ExecutionLogEntity execution = runningExecution(600L + keys.size());
            execution.setShardIndex(invocation.getArgument(4));
            execution.setShardTotal(2);
            return new CreatedExecution(execution, true);
        });
        when(executorClient.execute(anyString(), any(ExecuteRequest.class))).thenReturn(ExecuteAck.ok());
        runTriggerInline();

        triggerService.triggerAsync(1L, LeaderContext.none(), SCHEDULED_TIME);

        assertThat(keys).containsExactlyInAnyOrder("1:20260101100000:0", "1:20260101100000:1");
    }

    @Test
    void duplicateScheduledTriggerIsSkippedByIdempotencyKey() {
        // 同一个计划时间点重复触发：幂等键撞唯一索引，created=false，不再执行一遍。
        TaskEntity task = enabledTask();
        ExecutionLogEntity existing = runningExecution(700L);
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(onlineExecutor()));
        when(executionService.createRunning(eq(task), any(ExecutorEntity.class), eq(TriggerType.CRON),
                any(), anyInt(), anyInt(), anyString())).thenReturn(new CreatedExecution(existing, false));
        runTriggerInline();

        triggerService.triggerAsync(1L, LeaderContext.none(), SCHEDULED_TIME);

        verify(executorClient, never()).execute(anyString(), any(ExecuteRequest.class));
    }

    /**
     * 让被 mock 的触发线程池直接同步执行，方便断言定时触发的结果。
     */
    private void runTriggerInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(triggerExecutor).execute(any(Runnable.class));
    }

    private TaskEntity enabledTask() {
        TaskEntity task = new TaskEntity();
        task.setId(1L);
        task.setTaskName("demo-task");
        task.setHandlerName("demoHandler");
        task.setHandlerParam("{\"message\":\"hi\"}");
        task.setShardTotal(1);
        task.setTimeoutSeconds(30);
        task.setStatus(TaskStatus.ENABLED);
        return task;
    }

    private ExecutorEntity onlineExecutor() {
        ExecutorEntity executor = new ExecutorEntity();
        executor.setId(10L);
        executor.setAddress("127.0.0.1:8081");
        return executor;
    }

    private ExecutionLogEntity runningExecution(Long id) {
        ExecutionLogEntity execution = new ExecutionLogEntity();
        execution.setId(id);
        execution.setTaskId(1L);
        execution.setTaskName("demo-task");
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setStartTime(LocalDateTime.now().minusSeconds(1));
        return execution;
    }
}
