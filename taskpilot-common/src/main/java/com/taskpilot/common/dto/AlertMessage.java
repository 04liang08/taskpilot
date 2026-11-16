package com.taskpilot.common.dto;

import java.time.LocalDateTime;

/**
 * 告警消息：调度中心发现"需要人看一眼"的事情时广播出去。
 * <p>走 fanout 交换机：一份告警同时送给所有订阅方（webhook、未来的 IM 机器人等），
 * 订阅方之间互不影响。</p>
 *
 * @param alertType  告警类型，如 DEAD_LETTER
 * @param executionId 相关的执行记录 ID（可能为 null）
 * @param taskId     任务 ID
 * @param taskName   任务名
 * @param message    告警正文
 * @param createdAt  告警产生时间
 */
public record AlertMessage(
        String alertType,
        Long executionId,
        Long taskId,
        String taskName,
        String message,
        LocalDateTime createdAt) {
}
