package com.taskpilot.executor.mq;

import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.metrics.MetricNames;
import com.taskpilot.common.mq.MqTopology;
import com.taskpilot.common.mq.ResultChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 执行结果发布：把执行结果消息发到 results 交换机。
 *
 * <p>三条硬要求：</p>
 * <ul>
 *   <li><b>持久化</b>：消息标记为 PERSISTENT，broker 重启后不会丢；</li>
 *   <li><b>发布确认</b>：必须等 broker 确认收到才算发出去，
 *       否则"发成功"的假象会让调度中心永远等不到结果；</li>
 *   <li><b>失败要重试</b>：退避重发 3 次，全失败才放弃。</li>
 * </ul>
 *
 * <p>放弃之后只记 error 日志，不做本地磁盘队列：调度中心有僵尸回收兜底，
 * 会把迟迟没有结果的记录收成 TIMEOUT 并重试。</p>
 */
@Component
public class RabbitResultChannel implements ResultChannel {

    private static final Logger log = LoggerFactory.getLogger(RabbitResultChannel.class);

    /** 发布失败重试次数（含首次）。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 等发布确认的超时。 */
    private static final long CONFIRM_TIMEOUT_MS = 5000L;

    /** 重试间隔基数：第 n 次失败后等 n × RETRY_DELAY_MS。 */
    private static final long RETRY_DELAY_MS = 200L;

    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;

    public RabbitResultChannel(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void publish(ExecutionResultMessage message) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                sendOnce(message);
                Counter.builder(MetricNames.RESULTS_PUBLISHED)
                        .description("结果消息发布结局（confirmed=拿到 broker 确认；gave_up=重试耗尽显式放弃，等僵尸回收兜底）")
                        .tag("outcome", "confirmed")
                        .register(meterRegistry)
                        .increment();
                log.info("execution result published: executionId={}, taskId={}, status={}",
                        message.executionId(), message.taskId(), message.status());
                return;
            } catch (Exception e) {
                log.warn("publish execution result failed (attempt {}/{}): executionId={}, reason={}",
                        attempt, MAX_ATTEMPTS, message.executionId(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(RETRY_DELAY_MS * attempt);
                }
            }
        }
        // 到这里结果消息确实丢了。调度中心的僵尸回收会兜底：
        // 记录一直停在 RUNNING，超时后被标成 TIMEOUT 并进入重试。
        Counter.builder(MetricNames.RESULTS_PUBLISHED)
                .description("结果消息发布结局（confirmed=拿到 broker 确认；gave_up=重试耗尽显式放弃，等僵尸回收兜底）")
                .tag("outcome", "gave_up")
                .register(meterRegistry)
                .increment();
        log.error("publish execution result gave up: executionId={}, taskId={}, status={}",
                message.executionId(), message.taskId(), message.status());
    }

    /**
     * 发一次并等发布确认。单独拆出来是为了单测能替换掉真实的 broker 连接。
     */
    void sendOnce(ExecutionResultMessage message) {
        rabbitTemplate.invoke(operations -> {
            operations.convertAndSend(MqTopology.RESULTS_EXCHANGE, MqTopology.RESULTS_ROUTING_KEY, message,
                    amqpMessage -> {
                        amqpMessage.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return amqpMessage;
                    });
            // 没等到 broker 的确认，就不算发出去。
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
