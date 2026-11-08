package com.taskpilot.common.enums;

/**
 * 单次执行的状态机：RUNNING -> SUCCESS / FAILED / TIMEOUT / CANCELED。
 */
public enum ExecutionStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELED
}