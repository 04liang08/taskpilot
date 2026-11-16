package com.taskpilot.scheduler.service;

import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.shard.ShardFailedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryServiceTest {

    @Mock
    private TaskService taskService;

    @Mock
    private ExecutionService executionService;

    @Mock
    private ExecutorService executorService;

    @Mock
    private TriggerService triggerService;

    @Mock
    private ScheduledExecutorService retryScheduler;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ThreadPoolTaskExecutor shardExecutor;
    private RetryService retryService;

    @BeforeEach
    void setUp() {
        // 真实线程池：重试的执行是异步提交到这里的，测试要能看出线程边界。
        shardExecutor = new ThreadPoolTaskExecutor();
        shardExecutor.setCorePoolSize(2);
        shardExecutor.setMaxPoolSize(4);
        shardExecutor.setQueueCapacity(16);
        shardExecutor.setThreadNamePrefix("taskpilot-shard-");
        shardExecutor.initialize();
        retryService = new RetryService(taskService, executionService, executorService,
                triggerService, retryScheduler, shardExecutor, eventPublisher);
    }

    @AfterEach
    void tearDown() {
        shardExecutor.shutdown();
    }

    @Test
    void schedulesBackoffRetryWhenExecutorStillOnline() {
        ExecutionLogEntity execution = failedExecution();
        TaskEntity task = task();
        when(executionService.get(100L)).thenReturn(execution);
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(executorService.listOnline()).thenReturn(List.of(executor(10L)));

        retryService.onShardFailed(new ShardFailedEvent(100L, 1L, 10L));

        verify(executionService).markFailedForRetry(eq(execution), anyString(), any(LocalDateTime.class));
    }

    @Test
    void skipsBackoffRetryWhenExecutorIsGone() {
        ExecutionLogEntity execution = failedExecution();
        TaskEntity task = task();
        when(executionService.get(100L)).thenReturn(execution);
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(executorService.listOnline()).thenReturn(List.of());

        retryService.onShardFailed(new ShardFailedEvent(100L, 1L, 10L));

        // 原执行器没了：交给失败转移换机器，不在这里耗重试预算。
        verify(executionService, never()).markFailedForRetry(any(), anyString(), any());
    }

    @Test
    void skipsBackoffRetryWhenBudgetExhausted() {
        ExecutionLogEntity execution = failedExecution();
        TaskEntity task = task();
        when(executionService.get(100L)).thenReturn(execution);
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(false);

        retryService.onShardFailed(new ShardFailedEvent(100L, 1L, 10L));

        verify(executionService, never()).markFailedForRetry(any(), anyString(), any());
        // 重试预算耗尽 = 终态：落死信并告警，不能悄悄消失。
        verify(executionService).markRetryExhausted(eq(execution), eq("重试预算耗尽"));
    }

    @Test
    void scanPendingRetriesRetriesDueRecords() throws Exception {
        ExecutionLogEntity execution = failedExecution();
        TaskEntity task = task();
        CountDownLatch dispatched = new CountDownLatch(1);
        when(executionService.listPendingRetries()).thenReturn(List.of(execution));
        when(executionService.get(100L)).thenReturn(execution);
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(executorService.listOnline()).thenReturn(List.of(executor(10L)));
        when(executionService.claimForRetry(execution)).thenReturn(true);
        doAnswer(invocation -> {
            dispatched.countDown();
            return null;
        }).when(triggerService).dispatch(any(), any(), any(), any());

        retryService.scanPendingRetries();

        assertThat(dispatched.await(5, TimeUnit.SECONDS)).isTrue();
        verify(triggerService).dispatch(eq(execution), eq(task), any(ExecutorEntity.class), eq(TriggerType.CRON));
    }

    @Test
    void dispatchDoesNotRunOnTheSchedulingThread() throws Exception {
        // @Scheduled 默认单线程：在这里同步发 HTTP 会把健康检查、僵尸回收一起堵住。
        ExecutionLogEntity execution = failedExecution();
        TaskEntity task = task();
        CountDownLatch dispatched = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        when(executionService.listPendingRetries()).thenReturn(List.of(execution));
        when(executionService.get(100L)).thenReturn(execution);
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(executorService.listOnline()).thenReturn(List.of(executor(10L)));
        when(executionService.claimForRetry(execution)).thenReturn(true);
        doAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            dispatched.countDown();
            return null;
        }).when(triggerService).dispatch(any(), any(), any(), any());

        retryService.scanPendingRetries();

        assertThat(dispatched.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("taskpilot-shard-");
    }

    @Test
    void rejectedDispatchReleasesClaimSoFallbackScanCanRetry() {
        // 分片池满了：既不能把记录卡在 RUNNING，也不能丢掉这次重试。
        ExecutionLogEntity execution = failedExecution();
        TaskEntity task = task();
        ThreadPoolTaskExecutor rejecting = mock(ThreadPoolTaskExecutor.class);
        doThrow(new TaskRejectedException("shard pool is full")).when(rejecting).execute(any(Runnable.class));
        RetryService service = new RetryService(taskService, executionService, executorService,
                triggerService, retryScheduler, rejecting, eventPublisher);

        when(executionService.listPendingRetries()).thenReturn(List.of(execution));
        when(executionService.get(100L)).thenReturn(execution);
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(executorService.listOnline()).thenReturn(List.of(executor(10L)));
        when(executionService.claimForRetry(execution)).thenReturn(true);

        service.scanPendingRetries();

        verify(executionService).releaseRetryClaim(eq(execution), any(LocalDateTime.class), anyString());
        verify(triggerService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void originalExecutorGoneHandsOverToFailoverInsteadOfDroppingTheRetry() {
        // 原执行器已经下线：不能默默放弃这次重试，要交给失败转移换机。
        ExecutionLogEntity execution = failedExecution();
        TaskEntity task = task();
        when(executionService.listPendingRetries()).thenReturn(List.of(execution));
        when(executionService.get(100L)).thenReturn(execution);
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(executorService.listOnline()).thenReturn(List.of());

        retryService.scanPendingRetries();

        verify(eventPublisher).publishEvent(any(ShardFailedEvent.class));
        verify(triggerService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void exhaustedRetryBudgetMarksTheRecordTerminal() {
        // 预算耗尽：这条记录就是终态了，不能一直被扫描捞回来。
        ExecutionLogEntity execution = failedExecution();
        TaskEntity task = task();
        when(executionService.listPendingRetries()).thenReturn(List.of(execution));
        when(executionService.get(100L)).thenReturn(execution);
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(false);

        retryService.scanPendingRetries();

        verify(executionService).markRetryExhausted(execution);
        verify(eventPublisher, never()).publishEvent(any(ShardFailedEvent.class));
    }

    private ExecutionLogEntity failedExecution() {
        ExecutionLogEntity execution = new ExecutionLogEntity();
        execution.setId(100L);
        execution.setTaskId(1L);
        execution.setTriggerType(TriggerType.CRON);
        execution.setExecutorId(10L);
        execution.setExecutorAddress("127.0.0.1:8081");
        execution.setShardIndex(0);
        execution.setShardTotal(1);
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setRetryCount(0);
        execution.setStartTime(LocalDateTime.now().minusSeconds(1));
        return execution;
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity();
        task.setId(1L);
        task.setTaskName("demo");
        task.setStatus(TaskStatus.ENABLED);
        task.setHandlerName("demoHandler");
        task.setTimeoutSeconds(30);
        task.setShardTotal(1);
        task.setRetryMax(3);
        task.setRetryIntervalSeconds(10);
        return task;
    }

    private ExecutorEntity executor(Long id) {
        ExecutorEntity executor = new ExecutorEntity();
        executor.setId(id);
        executor.setAddress("127.0.0.1:8081");
        return executor;
    }
}
