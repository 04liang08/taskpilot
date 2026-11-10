package com.taskpilot.executor.dto;

/**
 * 向调度中心注册自身。
 */
public record RegisterRequest(String appName, String executorGroup, String address) {
}
