package com.taskpilot.scheduler.mq;

import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.scheduler.service.ResultProcessor;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExecutionResultListenerTest {

    @Mock
    private ResultProcessor resultProcessor;

    @Mock
    private Channel channel;

    private ExecutionResultListener listener;

    @BeforeEach
    void setUp() {
        listener = new ExecutionResultListener(resultProcessor);
    }

    @Test
    void acksAfterSuccessfulProcessing() throws IOException {
        ExecutionResultMessage message = message();

        listener.onResult(message, 7L, channel);

        verify(resultProcessor).process(message);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void nacksToDeadLetterWhenProcessingFails() throws IOException {
        // 处理失败不能无限重投：nack(requeue=false) 让消息进死信队列。
        doThrow(new IllegalStateException("db down")).when(resultProcessor).process(any());

        listener.onResult(message(), 8L, channel);

        verify(channel).basicNack(8L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private ExecutionResultMessage message() {
        return new ExecutionResultMessage(100L, 1L, ExecutionStatus.SUCCESS, 12L,
                null, "ok", "127.0.0.1:8081", null);
    }
}
