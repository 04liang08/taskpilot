package com.taskpilot.scheduler.service;

import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.shard.ShardFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private RetryService retryService;

    @BeforeEach
    void setUp() {
        retryService = new RetryService(taskService, executionService, executorService,
                triggerService, retryScheduler);
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
    }

    @Test
    void scanPendingRetriesRetriesDueRecords() {
        ExecutionLogEntity execution = failedExecution();
        TaskEntity task = task();
        when(executionService.listPendingRetries()).thenReturn(List.of(execution));
        when(executionService.get(100L)).thenReturn(execution);
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(executorService.listOnline()).thenReturn(List.of(executor(10L)));
        when(executionService.claimForRetry(execution)).thenReturn(true);

        retryService.scanPendingRetries();

        verify(triggerService).dispatch(eq(execution), eq(task), any(ExecutorEntity.class), eq(TriggerType.CRON));
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
