package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.shard.ShardFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResultProcessorTest {

    @Mock
    private ExecutionService executionService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ResultProcessor resultProcessor;

    @BeforeEach
    void setUp() {
        resultProcessor = new ResultProcessor(executionService, eventPublisher);
    }

    @Test
    void successfulResultFinalizesRecordWithoutFailureEvent() {
        ExecutionLogEntity execution = running(100L, TriggerType.CRON);
        when(executionService.get(100L)).thenReturn(execution);
        when(executionService.finishIfRunning(eq(execution), eq(ExecutionStatus.SUCCESS), eq(12L), any(), any()))
                .thenReturn(true);

        resultProcessor.process(message(ExecutionStatus.SUCCESS));

        verify(executionService).finishIfRunning(eq(execution), eq(ExecutionStatus.SUCCESS), eq(12L), any(), any());
        verify(eventPublisher, never()).publishEvent(any(ShardFailedEvent.class));
    }

    @Test
    void failedCronResultTriggersRetryEvent() {
        ExecutionLogEntity execution = running(100L, TriggerType.CRON);
        when(executionService.get(100L)).thenReturn(execution);
        when(executionService.finishIfRunning(eq(execution), eq(ExecutionStatus.FAILED), anyLong(), any(), any()))
                .thenReturn(true);

        resultProcessor.process(message(ExecutionStatus.FAILED));

        verify(eventPublisher).publishEvent(any(ShardFailedEvent.class));
    }

    @Test
    void failedManualResultDoesNotTriggerRetryEvent() {
        // 手动触发失败就是失败，由用户决定要不要重来：不进重试 / 失败转移链路。
        ExecutionLogEntity execution = running(100L, TriggerType.MANUAL);
        when(executionService.get(100L)).thenReturn(execution);
        when(executionService.finishIfRunning(eq(execution), eq(ExecutionStatus.FAILED), anyLong(), any(), any()))
                .thenReturn(true);

        resultProcessor.process(message(ExecutionStatus.FAILED));

        verify(eventPublisher, never()).publishEvent(any(ShardFailedEvent.class));
    }

    @Test
    void duplicateDeliveryDoesNotOverwriteTerminalState() {
        // 结果消息重复投递：finish 的 status=RUNNING 前置条件挡住了二次覆写，
        // 也不会重复发失败事件。
        ExecutionLogEntity execution = running(100L, TriggerType.CRON);
        when(executionService.get(100L)).thenReturn(execution);
        when(executionService.finishIfRunning(eq(execution), eq(ExecutionStatus.FAILED), anyLong(), any(), any()))
                .thenReturn(false);

        resultProcessor.process(message(ExecutionStatus.FAILED));

        verify(eventPublisher, never()).publishEvent(any(ShardFailedEvent.class));
    }

    @Test
    void dispatchFailureOfCronAlsoTriggersRetryEvent() {
        ExecutionLogEntity execution = running(100L, TriggerType.CRON);
        when(executionService.finishIfRunning(eq(execution), eq(ExecutionStatus.FAILED), anyLong(), any(), any()))
                .thenReturn(true);
        when(executionService.get(100L)).thenReturn(execution);

        resultProcessor.processDispatchFailure(execution, ExecutionStatus.FAILED, "调用执行器失败");

        verify(eventPublisher).publishEvent(any(ShardFailedEvent.class));
    }

    private ExecutionResultMessage message(ExecutionStatus status) {
        return new ExecutionResultMessage(100L, 1L, status, 12L,
                status == ExecutionStatus.SUCCESS ? null : "boom",
                status == ExecutionStatus.SUCCESS ? "ok" : null,
                "127.0.0.1:8081", LocalDateTime.now());
    }

    private ExecutionLogEntity running(Long id, TriggerType triggerType) {
        ExecutionLogEntity execution = new ExecutionLogEntity();
        execution.setId(id);
        execution.setTaskId(1L);
        execution.setTriggerType(triggerType);
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setStartTime(LocalDateTime.now().minusSeconds(1));
        return execution;
    }
}
