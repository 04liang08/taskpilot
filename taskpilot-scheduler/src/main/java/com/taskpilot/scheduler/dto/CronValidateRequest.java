package com.taskpilot.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * cron 校验请求。
 */
@Data
public class CronValidateRequest {

    @NotBlank(message = "expression 不能为空")
    @Size(max = 64, message = "expression 长度不能超过 64")
    private String expression;
}