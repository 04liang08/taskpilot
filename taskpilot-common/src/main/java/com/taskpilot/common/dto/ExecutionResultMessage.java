package com.taskpilot.common.dto;

import com.taskpilot.common.enums.ExecutionStatus;

import java.time.LocalDateTime;

/**
 * 执行结果消息：执行器执行完一条分片后，通过 RabbitMQ 回传给调度中心。
 * <p>调度中心收到后把执行记录从 RUNNING 收成终态，并按需触发重试 / 失败转移。</p>
 *
 * @param executionId     执行记录 ID
 * @param taskId          任务 ID
 * @param status          执行终态：SUCCESS / FAILED / TIMEOUT
 * @param durationMs      执行耗时（毫秒）
 * @param errorMessage    失败原因（成功时为 null）
 * @param resultSummary   结果摘要（成功时为 null）
 * @param executorAddress 执行该分片的执行器地址
 * @param reportedAt      执行器上报时间
 */
public record ExecutionResultMessage(
        Long executionId,
        Long taskId,
        ExecutionStatus status,
        Long durationMs,
        String errorMessage,
        String resultSummary,
        String executorAddress,
        LocalDateTime reportedAt) {
}
