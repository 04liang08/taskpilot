package com.taskpilot.scheduler.dto;

import com.taskpilot.common.enums.ExecutorStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 执行器分页查询条件。
 */
@Data
public class ExecutorQuery {

    @Min(value = 1, message = "page 必须大于等于 1")
    private long page = 1;

    @Min(value = 1, message = "size 必须大于等于 1")
    @Max(value = 100, message = "size 不能超过 100")
    private long size = 20;

    private ExecutorStatus status;

    @Size(max = 64, message = "group 长度不能超过 64")
    private String group;
}
