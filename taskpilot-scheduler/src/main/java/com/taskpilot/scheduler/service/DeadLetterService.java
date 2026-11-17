package com.taskpilot.scheduler.service;

import com.taskpilot.scheduler.entity.DeadLetterEntity;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.mapper.DeadLetterMapper;
import com.taskpilot.common.dto.AlertMessage;
import com.taskpilot.common.metrics.MetricNames;
import com.taskpilot.common.mq.AlertChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 死信记录：重试预算耗尽、或结果消息处理失败（毒消息）的执行记录，落一条 tp_dead_letter。
 *
 * <p>按 {@code execution_id} 幂等：同一条执行记录只落一行，重复上报不会堆重复行，
 * 也不会重复告警。</p>
 * <p>落死信的同时广播一条告警（fanout），由 webhook 消费者推给外部系统；
 * 没配 webhook 时只记日志。</p>
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    /** 死信告警类型。 */
    public static final String ALERT_TYPE_DEAD_LETTER = "DEAD_LETTER";

    private static final int LAST_ERROR_MAX_LENGTH = 2000;

    private final DeadLetterMapper deadLetterMapper;
    private final AlertChannel alertChannel;
    private final MeterRegistry meterRegistry;

    public DeadLetterService(DeadLetterMapper deadLetterMapper, AlertChannel alertChannel,
                             MeterRegistry meterRegistry) {
        this.deadLetterMapper = deadLetterMapper;
        this.alertChannel = alertChannel;
        this.meterRegistry = meterRegistry;
    }

    /** 死信计数：outcome=recorded|duplicate。 */
    private void countDeadLetter(String outcome) {
        Counter.builder(MetricNames.DEADLETTERS)
                .description("死信落库次数（幂等跳过另记 duplicate）")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录一条死信并广播告警。
     *
     * @return true 表示这次真的新落了一条死信；false 表示已存在（或记录不完整）没有重复落
     */
    public boolean record(ExecutionLogEntity execution, String reason) {
        if (execution == null || execution.getId() == null) {
            log.warn("dead letter skipped, execution record missing: reason={}", reason);
            return false;
        }
        DeadLetterEntity entity = new DeadLetterEntity();
        entity.setExecutionId(execution.getId());
        entity.setTaskId(execution.getTaskId());
        entity.setTaskName(execution.getTaskName() == null ? "" : execution.getTaskName());
        entity.setShardIndex(execution.getShardIndex() == null ? 0 : execution.getShardIndex());
        entity.setShardTotal(execution.getShardTotal() == null ? 1 : execution.getShardTotal());
        entity.setRetryCount(execution.getRetryCount() == null ? 0 : execution.getRetryCount());
        entity.setLastError(truncate(reason, LAST_ERROR_MAX_LENGTH));
        entity.setDeadTime(LocalDateTime.now());
        try {
            deadLetterMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 同一条执行记录已经有死信了：幂等，不重复落库也不重复告警。
            countDeadLetter("duplicate");
            log.info("dead letter already recorded, skip: executionId={}", execution.getId());
            return false;
        }
        countDeadLetter("recorded");
        log.error("dead letter recorded: executionId={}, taskId={}, shard={}/{}, retryCount={}, reason={}",
                execution.getId(), execution.getTaskId(), entity.getShardIndex(), entity.getShardTotal(),
                entity.getRetryCount(), reason);
        alertChannel.publish(new AlertMessage(ALERT_TYPE_DEAD_LETTER,
                execution.getId(),
                execution.getTaskId(),
                entity.getTaskName(),
                "执行记录进入死信: " + reason,
                LocalDateTime.now()));
        return true;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
