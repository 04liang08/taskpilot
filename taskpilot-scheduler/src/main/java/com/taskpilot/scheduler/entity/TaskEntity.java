package com.taskpilot.scheduler.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务定义，对应 tp_task。
 */
@Getter
@Setter
@TableName("tp_task")
public class TaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskName;

    private String taskGroup;

    private String cronExpression;

    private TriggerType triggerType;

    private String handlerName;

    private String handlerParam;

    private Integer shardTotal;

    private Integer timeoutSeconds;

    private Integer retryMax;

    private Integer retryIntervalSeconds;

    private TaskStatus status;

    private LocalDateTime nextTriggerTime;

    private LocalDateTime lastTriggerTime;

    private String description;

    @Version
    private Integer version;

    @TableLogic
    @JsonIgnore
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}