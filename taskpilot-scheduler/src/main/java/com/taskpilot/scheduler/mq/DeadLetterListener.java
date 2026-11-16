package com.taskpilot.scheduler.mq;

import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.mq.MqTopology;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.service.DeadLetterService;
import com.taskpilot.scheduler.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 死信消费者：处理失败的结果消息（毒消息）落进 tp_dead_letter。
 *
 * <p>结果队列挂了 DLX：消息消费失败 → nack(requeue=false) → 进死信队列 → 这里。
 * 毒消息留在队列里只会一直重投，落一张死信表 + 一条告警才是能查、能追的处理方式。</p>
 */
@Component
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final ExecutionService executionService;
    private final DeadLetterService deadLetterService;

    public DeadLetterListener(ExecutionService executionService, DeadLetterService deadLetterService) {
        this.executionService = executionService;
        this.deadLetterService = deadLetterService;
    }

    @RabbitListener(queues = MqTopology.DEAD_QUEUE)
    public void onDeadLetter(ExecutionResultMessage message) {
        ExecutionLogEntity execution = findExecution(message);
        if (execution == null) {
            log.error("dead letter message without execution record: executionId={}", message.executionId());
            return;
        }
        deadLetterService.record(execution, "结果消息处理失败，已进入死信队列");
    }

    private ExecutionLogEntity findExecution(ExecutionResultMessage message) {
        if (message == null || message.executionId() == null) {
            return null;
        }
        try {
            return executionService.get(message.executionId());
        } catch (Exception e) {
            log.warn("dead letter message references unknown execution: executionId={}", message.executionId());
            return null;
        }
    }
}
