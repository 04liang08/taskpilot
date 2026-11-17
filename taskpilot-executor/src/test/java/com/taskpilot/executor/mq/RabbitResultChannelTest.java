package com.taskpilot.executor.mq;

import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
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
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RabbitResultChannel channel = spy(new RabbitResultChannel(rabbitTemplate, meterRegistry));

        channel.publish(message());

        verify(channel, times(1)).sendOnce(any());
        // 4.10.4：broker 确认才算 confirmed（ADR-022）。
        assertThat(meterRegistry.get(MetricNames.RESULTS_PUBLISHED).tag("outcome", "confirmed")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void retriesThreeTimesThenGivesUp() {
        // broker 一直不可达：退避重发 3 次，全失败后只记 error 日志。
        // 结果消息丢了不阻塞执行器——调度中心的僵尸回收会兜底收成 TIMEOUT。
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RabbitResultChannel channel = spy(new RabbitResultChannel(rabbitTemplate, meterRegistry));
        doThrow(new AmqpException("broker unreachable")).when(channel).sendOnce(any());

        channel.publish(message());

        verify(channel, times(3)).sendOnce(any());
        // 显式放弃单独计数，不混进 confirmed。
        assertThat(meterRegistry.get(MetricNames.RESULTS_PUBLISHED).tag("outcome", "gave_up")
                .counter().count()).isEqualTo(1.0);
    }

    private ExecutionResultMessage message() {
        return new ExecutionResultMessage(100L, 1L, ExecutionStatus.SUCCESS, 12L,
                null, "ok", "127.0.0.1:8081", null);
    }
}
