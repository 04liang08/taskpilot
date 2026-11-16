package com.taskpilot.scheduler.mq;

import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.mq.MqTopology;
import com.taskpilot.scheduler.service.ResultProcessor;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 执行结果消费者：执行器跑完分片后把结果消息发过来，这里收终态。
 *
 * <p>手动 ACK 的原因：</p>
 * <ul>
 *   <li>处理成功 → basicAck。ACK 发生在 {@code ResultProcessor.process} 返回之后，
 *       而后者带事务，所以"DB 提交"一定早于"ack"，不会出现 ack 了但数据没落库；</li>
 *   <li>处理失败 → basicNack(requeue=false)，消息自动进死信队列（DLX），
 *       由死信消费者落死信表并告警，不在队列里无限重投。</li>
 * </ul>
 */
@Component
public class ExecutionResultListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionResultListener.class);

    private final ResultProcessor resultProcessor;

    public ExecutionResultListener(ResultProcessor resultProcessor) {
        this.resultProcessor = resultProcessor;
    }

    @RabbitListener(queues = MqTopology.RESULTS_QUEUE, ackMode = "MANUAL")
    public void onResult(ExecutionResultMessage message,
                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                         Channel channel) throws IOException {
        try {
            resultProcessor.process(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("handle execution result failed, message goes to dead letter: executionId={}",
                    message == null ? null : message.executionId(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
