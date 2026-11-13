package com.taskpilot.scheduler.leader;

/**
 * leader 身份变化事件：竞选成功或失去 leader 时发布，调度器据此启停时间轮。
 *
 * @param leader      是否已经成为 leader
 * @param leaderToken 当前 leader 的 fencing token
 */
public record LeaderChangedEvent(boolean leader, Long leaderToken) {
}
