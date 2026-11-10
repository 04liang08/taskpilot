package com.taskpilot.executor.dto;

/**
 * 向调度中心发送心跳。
 */
public record HeartbeatRequest(String appName, String address) {
}
