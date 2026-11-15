package com.taskpilot.scheduler.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TriggerType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 执行记录，对应 tp_execution_log。
 */
@Getter
@Setter
@TableName("tp_execution_log")
public class ExecutionLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String executionKey;

    private Long taskId;

    private String taskName;

    private TriggerType triggerType;

    private LocalDateTime triggerTime;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long executorId;

    private String executorAddress;

    private Integer shardIndex;

    private Integer shardTotal;

    private ExecutionStatus status;

    private Integer retryCount;

    private Long fencingToken;

    private String leaderInstance;

    private LocalDateTime nextRetryTime;

    private Long durationMs;

    private String errorMessage;

    private String resultSummary;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}