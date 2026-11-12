package com.taskpilot.scheduler.schedule;

/**
 * 时间轮内存里缓存的任务调度信息。
 * <p>只放推进下一次触发必需的字段，避免每触发一次都回查任务定义。</p>
 */
public record ScheduledTask(Long taskId, String taskName, String cronExpression) {
}
