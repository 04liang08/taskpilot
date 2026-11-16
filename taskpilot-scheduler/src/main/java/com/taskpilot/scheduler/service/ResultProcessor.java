package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.shard.ShardFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 执行结果的统一处理：落终态 + 该失败就发失败事件。
 *
 * <p>第 4 周起执行结果是异步回传的，有两条入口，语义必须一致：</p>
 * <ol>
 *   <li>{@link #process(ExecutionResultMessage)}：执行器跑完后经 RabbitMQ 回传的结果消息；</li>
 *   <li>{@link #processDispatchFailure}：调度侧直接拿到的失败（执行器拒绝受理、
 *       连不上、ACK 超时）。</li>
 * </ol>
 * <p>两条路径都只改终态列、都带 {@code status=RUNNING} 前置条件，
 * 所以结果消息重复投递也只会生效一次。</p>
 */
@Service
public class ResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(ResultProcessor.class);

    private final ExecutionService executionService;
    private final ApplicationEventPublisher eventPublisher;

    public ResultProcessor(ExecutionService executionService, ApplicationEventPublisher eventPublisher) {
        this.executionService = executionService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 处理执行器回传的执行结果。
     * <p>方法本身带事务：监听器在方法返回后才 basicAck，
     * 于是"DB 提交"一定发生在"ack"之前，不会出现 ack 了但数据没落库的窗口。</p>
     */
    @Transactional
    public void process(ExecutionResultMessage message) {
        ExecutionLogEntity execution = executionService.get(message.executionId());
        long durationMs = message.durationMs() == null ? 0L : message.durationMs();
        boolean transitioned = executionService.finishIfRunning(execution, message.status(), durationMs,
                message.errorMessage(), message.resultSummary());
        if (!transitioned) {
            // 重复投递，或者记录已经被下线收尾 / 其它路径收过了。
            log.info("execution result ignored, record already finalized: executionId={}, status={}",
                    message.executionId(), message.status());
            return;
        }
        log.info("execution result applied: executionId={}, taskId={}, shard={}/{}, status={}, durationMs={}, executor={}",
                message.executionId(), message.taskId(), execution.getShardIndex(), execution.getShardTotal(),
                message.status(), durationMs, message.executorAddress());
        if (isFailure(message.status())) {
            publishShardFailed(execution);
        }
    }

    /**
     * 调度侧直接拿到的失败：执行器拒绝受理、连不上执行器、ACK 超时。
     * <p>这类失败没有结果消息，调度侧自己把记录收成终态。</p>
     */
    public ExecutionLogEntity processDispatchFailure(ExecutionLogEntity execution,
                                                      ExecutionStatus status,
                                                      String errorMessage) {
        long durationMs = elapsedMs(execution.getStartTime());
        boolean transitioned = executionService.finishIfRunning(execution, status, durationMs, errorMessage, null);
        if (transitioned && isFailure(status)) {
            publishShardFailed(execution);
        }
        return executionService.get(execution.getId());
    }

    private boolean isFailure(ExecutionStatus status) {
        return status == ExecutionStatus.FAILED || status == ExecutionStatus.TIMEOUT;
    }

    /**
     * 发布分片失败事件，交给重试 / 失败转移。
     * <p>只有定时触发的记录才发：手动触发失败就是失败，由用户决定要不要重来。</p>
     */
    private void publishShardFailed(ExecutionLogEntity execution) {
        if (execution == null || execution.getTriggerType() != TriggerType.CRON) {
            return;
        }
        eventPublisher.publishEvent(
                new ShardFailedEvent(execution.getId(), execution.getTaskId(), execution.getExecutorId()));
    }

    private long elapsedMs(LocalDateTime startTime) {
        if (startTime == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(startTime, LocalDateTime.now()).toMillis());
    }
}
