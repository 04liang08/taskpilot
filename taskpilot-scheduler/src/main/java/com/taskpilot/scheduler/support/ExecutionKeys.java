package com.taskpilot.scheduler.support;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 执行记录的幂等键。
 * <p>手动触发用 UUID（允许重复触发）；定时触发用确定性键
 * {@code taskId:触发时间:分片号}，同一个计划时间点的重复触发会撞唯一索引被挡掉。</p>
 */
public final class ExecutionKeys {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private ExecutionKeys() {
    }

    public static String deterministic(Long taskId, LocalDateTime scheduledTime, int shardIndex) {
        return taskId + ":" + scheduledTime.format(TIME_FORMAT) + ":" + shardIndex;
    }
}
