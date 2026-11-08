package com.taskpilot.scheduler.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * cron 校验结果。非法表达式也返回 HTTP 200，由 valid 字段表达，方便页面直接展示。
 */
public record CronValidateResult(boolean valid, List<LocalDateTime> nextTimes, String message) {

    public static CronValidateResult valid(List<LocalDateTime> nextTimes) {
        return new CronValidateResult(true, nextTimes, "ok");
    }

    public static CronValidateResult invalid(String message) {
        return new CronValidateResult(false, List.of(), message);
    }
}