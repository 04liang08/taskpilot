package com.taskpilot.scheduler.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建任务请求。
 */
@Data
public class TaskCreateRequest {

    @NotBlank(message = "任务名称不能为空")
    @Size(max = 128, message = "任务名称长度不能超过 128")
    private String taskName;

    @Size(max = 64, message = "任务分组长度不能超过 64")
    private String taskGroup;

    @NotBlank(message = "cron 表达式不能为空")
    @Size(max = 64, message = "cron 表达式长度不能超过 64")
    private String cronExpression;

    @NotBlank(message = "处理器名称不能为空")
    @Size(max = 128, message = "处理器名称长度不能超过 128")
    private String handlerName;

    private String handlerParam;

    @Min(value = 1, message = "分片总数至少为 1")
    @Max(value = 1000, message = "分片总数不能超过 1000")
    private Integer shardTotal;

    @Min(value = 1, message = "超时时间至少为 1 秒")
    @Max(value = 86400, message = "超时时间不能超过 86400 秒")
    private Integer timeoutSeconds;

    @Min(value = 0, message = "重试次数不能为负数")
    @Max(value = 100, message = "重试次数不能超过 100")
    private Integer retryMax;

    @Min(value = 0, message = "重试间隔不能为负数")
    @Max(value = 86400, message = "重试间隔不能超过 86400 秒")
    private Integer retryIntervalSeconds;

    @Size(max = 512, message = "描述长度不能超过 512")
    private String description;
}