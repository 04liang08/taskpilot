package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.mapper.ExecutionLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionServiceTest {

    @Mock
    private ExecutionLogMapper executionLogMapper;

    @InjectMocks
    private ExecutionService executionService;

    @Test
    void createRunningPersistsRunningRecordWithUuid() {
        when(executionLogMapper.insert(any(ExecutionLogEntity.class))).thenAnswer(invocation -> {
            ((ExecutionLogEntity) invocation.getArgument(0)).setId(100L);
            return 1;
        });

        ExecutionLogEntity execution = executionService.createRunning(task(), executor());

        assertThat(execution.getId()).isEqualTo(100L);
        assertThat(execution.getExecutionKey()).isNotBlank();
        assertThat(execution.getTriggerType()).isEqualTo(TriggerType.MANUAL);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(execution.getExecutorId()).isEqualTo(10L);
        assertThat(execution.getExecutorAddress()).isEqualTo("127.0.0.1:8081");
        assertThat(execution.getStartTime()).isNotNull();
    }

    @Test
    void completeSuccessMarksSuccess() {
        ExecutionLogEntity execution = runningExecution(101L);
        stubUpdateAndReload(execution);

        ExecutionLogEntity result = executionService.complete(execution,
                ExecuteResult.success("demoHandler done", 12));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getDurationMs()).isEqualTo(12);
        assertThat(result.getResultSummary()).isEqualTo("demoHandler done");
        assertThat(result.getEndTime()).isNotNull();
        verify(executionLogMapper).updateById(execution);
    }

    @Test
    void completeFailureMarksFailed() {
        ExecutionLogEntity execution = runningExecution(102L);
        stubUpdateAndReload(execution);

        ExecutionLogEntity result = executionService.complete(execution,
                ExecuteResult.failure("处理器执行失败", 8));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("处理器执行失败");
        assertThat(result.getDurationMs()).isEqualTo(8);
    }

    @Test
    void completeTimeoutMarksTimeout() {
        ExecutionLogEntity execution = runningExecution(103L);
        stubUpdateAndReload(execution);

        ExecutionLogEntity result = executionService.complete(execution,
                ExecuteResult.timeout("执行超时（5s）", 5003));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(result.getErrorMessage()).isEqualTo("执行超时（5s）");
    }

    @Test
    void markFailedAfterConnectionErrorKeepsRecord() {
        ExecutionLogEntity execution = runningExecution(104L);
        stubUpdateAndReload(execution);

        ExecutionLogEntity result = executionService.markFailed(execution, "调用执行器失败: Connection refused");

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("Connection refused");
        assertThat(result.getEndTime()).isNotNull();
    }

    @Test
    void createFailedPersistsFailedRecordWithoutExecutor() {
        ArgumentCaptor<ExecutionLogEntity> captor = ArgumentCaptor.forClass(ExecutionLogEntity.class);
        when(executionLogMapper.insert(any(ExecutionLogEntity.class))).thenAnswer(invocation -> {
            ((ExecutionLogEntity) invocation.getArgument(0)).setId(105L);
            return 1;
        });

        ExecutionLogEntity execution = executionService.createFailed(task(), "没有可用的在线执行器");

        verify(executionLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("没有可用的在线执行器");
        assertThat(captor.getValue().getExecutorId()).isNull();
        assertThat(execution.getId()).isEqualTo(105L);
    }

    private void stubUpdateAndReload(ExecutionLogEntity execution) {
        when(executionLogMapper.updateById(execution)).thenReturn(1);
        when(executionLogMapper.selectById(execution.getId())).thenReturn(execution);
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity();
        task.setId(1L);
        task.setTaskName("demo-task");
        task.setHandlerName("demoHandler");
        task.setHandlerParam("{\"message\":\"hi\"}");
        task.setShardTotal(1);
        task.setTimeoutSeconds(30);
        return task;
    }

    private ExecutorEntity executor() {
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
