package com.taskpilot.scheduler.dto;

/**
 * 仪表盘概览。
 */
public record DashboardSummary(
        long taskCount,
        long enabledTaskCount,
        long onlineExecutorCount,
        long todaySuccess,
        long todayFailed,
        long runningCount) {
}