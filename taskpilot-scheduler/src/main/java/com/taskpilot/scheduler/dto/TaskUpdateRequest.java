package com.taskpilot.scheduler.dto;

import com.taskpilot.common.enums.TriggerType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskUpdateRequest(

        @NotBlank(message = "任务名称不能为空")
        @Size(max = 128, message = "任务名称不能超过 128 个字符")
        String taskName,

        @Size(max = 64, message = "任务分组不能超过 64 个字符")
        String taskGroup,

        @NotBlank(message = "cron 表达式不能为空")
        @Size(max = 64, message = "cron 表达式不能超过 64 个字符")
        String cronExpression,

        TriggerType triggerType,

        @NotBlank(message = "处理器名称不能为空")
        @Size(max = 128, message = "处理器名称不能超过 128 个字符")
        String handlerName,

        String handlerParam,

        @Min(value = 1, message = "分片总数至少为 1")
        @Max(value = 64, message = "分片总数不能超过 64")
        Integer shardTotal,

        @Min(value = 1, message = "超时时间至少为 1 秒")
        @Max(value = 3600, message = "超时时间不能超过 3600 秒")
        Integer timeoutSeconds,

        @Min(value = 0, message = "重试次数不能为负数")
        @Max(value = 10, message = "重试次数不能超过 10")
        Integer retryMax,

        @Min(value = 0, message = "重试间隔不能为负数")
        @Max(value = 3600, message = "重试间隔不能超过 3600 秒")
        Integer retryIntervalSeconds,

        @Size(max = 512, message = "描述不能超过 512 个字符")
        String description
) {
}
