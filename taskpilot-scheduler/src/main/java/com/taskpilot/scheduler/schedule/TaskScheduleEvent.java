package com.taskpilot.scheduler.schedule;

/**
 * 任务调度状态变化事件。任务新建/更新/启停/删除后发布，调度器据此重排时间轮。
 * <p>deleted 为 true 表示任务已删除，调度器需要把它从轮中移除。</p>
 */
public record TaskScheduleEvent(Long taskId, boolean deleted) {
}
