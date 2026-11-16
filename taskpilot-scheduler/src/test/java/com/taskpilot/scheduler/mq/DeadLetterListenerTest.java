package com.taskpilot.scheduler.mq;

import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.service.DeadLetterService;
import com.taskpilot.scheduler.service.ExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterListenerTest {

    @Mock
    private ExecutionService executionService;

    @Mock
    private DeadLetterService deadLetterService;

    private DeadLetterListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeadLetterListener(executionService, deadLetterService);
    }

    @Test
    void poisonMessageIsRecordedAsDeadLetter() {
        // 结果消息处理失败（毒消息）进死信队列，这里落一条死信记录。
        ExecutionLogEntity execution = new ExecutionLogEntity();
        execution.setId(100L);
        when(executionService.get(100L)).thenReturn(execution);

        listener.onDeadLetter(message(100L));

        verify(deadLetterService).record(eq(execution), any());
    }

    @Test
    void unknownExecutionRecordIsSkipped() {
        when(executionService.get(100L))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "执行记录不存在: 100"));

        listener.onDeadLetter(message(100L));

        verify(deadLetterService, never()).record(any(), any());
    }

    private ExecutionResultMessage message(Long executionId) {
        return new ExecutionResultMessage(executionId, 1L, ExecutionStatus.SUCCESS, 12L,
                null, "ok", "127.0.0.1:8081", null);
    }
}
