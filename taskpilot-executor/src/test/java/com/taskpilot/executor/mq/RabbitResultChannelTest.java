package com.taskpilot.executor.mq;

import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.enums.ExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitResultChannelTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    void publishesOnceWhenBrokerConfirms() {
        RabbitResultChannel channel = spy(new RabbitResultChannel(rabbitTemplate));

        channel.publish(message());

        verify(channel, times(1)).sendOnce(any());
    }

    @Test
    void retriesThreeTimesThenGivesUp() {
        // broker 一直不可达：退避重发 3 次，全失败后只记 error 日志。
        // 结果消息丢了不阻塞执行器——调度中心的僵尸回收会兜底收成 TIMEOUT。
        RabbitResultChannel channel = spy(new RabbitResultChannel(rabbitTemplate));
        doThrow(new AmqpException("broker unreachable")).when(channel).sendOnce(any());

        channel.publish(message());

        verify(channel, times(3)).sendOnce(any());
    }

    private ExecutionResultMessage message() {
        return new ExecutionResultMessage(100L, 1L, ExecutionStatus.SUCCESS, 12L,
                null, "ok", "127.0.0.1:8081", null);
    }
}
