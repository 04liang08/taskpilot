package com.taskpilot.scheduler.executor;

/**
 * 执行器上下线事件。
 *
 * @param executorId 执行器 id
 * @param address    执行器地址
 * @param online     true 表示上线（注册或从离线恢复），false 表示下线（心跳超时）
 */
public record ExecutorStatusChangedEvent(Long executorId, String address, boolean online) {
}
