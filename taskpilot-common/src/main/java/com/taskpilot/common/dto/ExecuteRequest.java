package com.taskpilot.common.dto;

/**
 * 调度中心调用执行器的请求体，scheduler / executor 共用的线协议。
 */
public record ExecuteRequest(
        Long executionId,
        Long taskId,
        String handlerName,
        String handlerParam,
        Integer shardIndex,
        Integer shardTotal,
        Integer timeoutSeconds) {
}
