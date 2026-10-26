package com.taskpilot.common.exception;

/**
 * 业务错误码。
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAM_ERROR(40000, "参数错误"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "冲突"),
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
