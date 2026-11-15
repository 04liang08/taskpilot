package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.dto.ExecuteResult;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriggerServiceTest {

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
    private ApplicationEventPublisher eventPublisher;

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
                leaderStateService, shardAllocatorRegistry, triggerExecutor, shardExecutor, eventPublisher);
    }

    @AfterEach
    void tearDown() {
        shardExecutor.shutdown();
    }

    @Test
    void triggerSelectsOnlineExecutorAndCompletesExecution() {
        TaskEntity task = enabledTask();
        ExecutorEntity executor = onlineExecutor();
        ExecutionLogEntity running = runningExecution(200L);
        ExecutionLogEntity finished = runningExecution(200L);
        finished.setStatus(ExecutionStatus.SUCCESS);
        finished.setDurationMs(9L);
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(executor));
        when(executionService.createRunning(eq(task), eq(executor), eq(TriggerType.MANUAL), any(), anyInt(), anyInt(),
                anyString())).thenReturn(new CreatedExecution(running, true));
        when(executorClient.execute(eq("127.0.0.1:8081"), any(ExecuteRequest.class)))
                .thenReturn(ExecuteResult.success("ok", 9));
        when(executionService.complete(eq(running), any(ExecuteResult.class))).thenReturn(finished);

        TriggerResult result = triggerService.trigger(1L);

        assertThat(result.executionId()).isEqualTo(200L);
        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.executions()).containsExactly(finished);
        ArgumentCaptor<ExecuteRequest> requestCaptor = ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(executorClient).execute(eq("127.0.0.1:8081"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().executionId()).isEqualTo(200L);
        assertThat(requestCaptor.getValue().handlerName()).isEqualTo("demoHandler");
        assertThat(requestCaptor.getValue().timeoutSeconds()).isEqualTo(30);
        verify(executionService).complete(eq(running), any(ExecuteResult.class));
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
        when(executionService.createFailed(eq(task), anyString(), any())).thenReturn(failed);

        assertThatThrownBy(() -> triggerService.trigger(1L))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getCode())
                        .isEqualTo(ErrorCode.EXECUTOR_UNAVAILABLE.getCode()))
                .hasMessageContaining("执行记录 ID=201");

        verify(executionService).createFailed(eq(task), eq("没有可用的在线执行器"), any());
        verify(executorClient, never()).execute(anyString(), any(ExecuteRequest.class));
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
        when(executionService.markFailed(eq(running), anyString())).thenReturn(failed);

        TriggerResult result = triggerService.trigger(1L);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.executions()).containsExactly(failed);
        verify(executionService).markFailed(eq(running), anyString());
        verify(executionService, never()).markTimeout(any(ExecutionLogEntity.class), anyString());
    }

    @Test
    void concurrentTriggerOfSameTaskIsRejected() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskEntity task = enabledTask();
        ExecutorEntity executor = onlineExecutor();
        ExecutionLogEntity running = runningExecution(203L);
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(executor));
        when(executionService.createRunning(eq(task), eq(executor), eq(TriggerType.MANUAL), any(), anyInt(), anyInt(),
                anyString())).thenReturn(new CreatedExecution(running, true));
        when(executorClient.execute(eq("127.0.0.1:8081"), any(ExecuteRequest.class)))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return ExecuteResult.success("ok", 1);
                });
        when(executionService.complete(eq(running), any(ExecuteResult.class))).thenReturn(running);

        java.util.concurrent.ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = pool.submit(() -> triggerService.trigger(1L));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> triggerService.trigger(1L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("正在执行中");

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
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
        when(executorClient.execute(anyString(), any(ExecuteRequest.class)))
                .thenReturn(ExecuteResult.success("ok", 5));
        when(executionService.complete(any(ExecutionLogEntity.class), any(ExecuteResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

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
            return ExecuteResult.success("ok", 1);
        });
        when(executionService.complete(any(ExecutionLogEntity.class), any(ExecuteResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TriggerResult result = triggerService.trigger(1L);

        assertThat(result.executions()).hasSize(2);
        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
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
