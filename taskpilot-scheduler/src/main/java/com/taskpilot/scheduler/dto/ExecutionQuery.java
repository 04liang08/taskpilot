package com.taskpilot.scheduler.dto;

import com.taskpilot.common.enums.ExecutionStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 执行记录分页查询条件。
 */
@Data
public class ExecutionQuery {

    @Min(value = 1, message = "page 必须大于等于 1")
    private long page = 1;

    @Min(value = 1, message = "size 必须大于等于 1")
    @Max(value = 100, message = "size 不能超过 100")
    private long size = 20;

    @Min(value = 1, message = "taskId 必须大于等于 1")
    private Long taskId;

    private ExecutionStatus status;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
}
