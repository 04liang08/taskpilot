package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.scheduler.client.ExecutorClient;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.leader.LeaderContext;
import com.taskpilot.scheduler.leader.LeaderStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    private ThreadPoolTaskExecutor triggerExecutor;

    @InjectMocks
    private TriggerService triggerService;

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
        when(executionService.createRunning(eq(task), eq(executor), eq(TriggerType.MANUAL), any()))
                .thenReturn(running);
        when(executorClient.execute(eq("127.0.0.1:8081"), any(ExecuteRequest.class)))
                .thenReturn(ExecuteResult.success("ok", 9));
        when(executionService.complete(eq(running), any(ExecuteResult.class))).thenReturn(finished);

        ExecutionLogEntity result = triggerService.trigger(1L);

        assertThat(result).isSameAs(finished);
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
        when(executionService.createRunning(eq(task), eq(executor), eq(TriggerType.MANUAL), any()))
                .thenReturn(running);
        when(executorClient.execute(eq("127.0.0.1:8081"), any(ExecuteRequest.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));
        when(executionService.markFailed(eq(running), anyString())).thenReturn(failed);

        ExecutionLogEntity result = triggerService.trigger(1L);

        assertThat(result).isSameAs(failed);
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
        when(executionService.createRunning(eq(task), eq(executor), eq(TriggerType.MANUAL), any()))
                .thenReturn(running);
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
