package com.taskpilot.common.dto;

/**
 * 执行器返回给调度中心的执行结果。
 */
public record ExecuteResult(
        boolean success,
        String resultSummary,
        String errorMessage,
        long durationMs,
        boolean timeout) {

    public static ExecuteResult success(String resultSummary, long durationMs) {
        return new ExecuteResult(true, resultSummary, null, durationMs, false);
    }

    public static ExecuteResult failure(String errorMessage, long durationMs) {
        return new ExecuteResult(false, null, errorMessage, durationMs, false);
    }

    public static ExecuteResult timeout(String errorMessage, long durationMs) {
        return new ExecuteResult(false, null, errorMessage, durationMs, true);
    }
}
