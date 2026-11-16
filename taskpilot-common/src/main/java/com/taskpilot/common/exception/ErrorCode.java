package com.taskpilot.common.exception;

/**
 * 业务错误码。HTTP 状态码与业务码同时返回，业务码保持 5 位便于前端统一处理。
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),
    BAD_REQUEST(40000, "参数错误"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "资源冲突"),
    UNAUTHORIZED(40100, "未授权"),
    EXECUTOR_UNAVAILABLE(50001, "执行器不可用"),
    INTERNAL_ERROR(50000, "服务内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}