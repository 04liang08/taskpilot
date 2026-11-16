package com.taskpilot.scheduler.mq;

import com.taskpilot.common.dto.AlertMessage;
import com.taskpilot.common.mq.AlertChannel;
import com.taskpilot.common.mq.MqTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 告警发布：广播到 fanout 交换机，一份告警送给所有订阅方。
 * <p>fanout 交换机忽略 routing key，绑上去就能收到。</p>
 */
@Component
public class RabbitAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(RabbitAlertChannel.class);

    private final RabbitTemplate rabbitTemplate;

    public RabbitAlertChannel(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(AlertMessage message) {
        try {
            rabbitTemplate.convertAndSend(MqTopology.ALERT_EXCHANGE, "", message);
            log.warn("alert published: type={}, executionId={}, taskId={}, message={}",
                    message.alertType(), message.executionId(), message.taskId(), message.message());
        } catch (Exception e) {
            // 告警发不出去不能把业务事务拖垮：死信已经落库了，日志能查到。
            log.error("publish alert failed: type={}, executionId={}, reason={}",
                    message.alertType(), message.executionId(), e.getMessage());
        }
    }
}
