package com.taskpilot.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 执行器注册请求。
 */
@Data
public class ExecutorRegisterRequest {

    @NotBlank(message = "appName 不能为空")
    @Size(max = 128, message = "appName 长度不能超过 128")
    private String appName;

    @Size(max = 64, message = "executorGroup 长度不能超过 64")
    private String executorGroup;

    @NotBlank(message = "address 不能为空")
    @Size(max = 128, message = "address 长度不能超过 128")
    @Pattern(regexp = "^[A-Za-z0-9._-]+:\\d{1,5}$", message = "address 必须是 host:port 格式")
    private String address;
}
