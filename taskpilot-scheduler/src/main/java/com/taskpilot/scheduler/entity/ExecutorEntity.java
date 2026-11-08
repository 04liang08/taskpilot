package com.taskpilot.scheduler.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.taskpilot.common.enums.ExecutorStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 执行器注册表，对应 tp_executor。
 */
@Getter
@Setter
@TableName("tp_executor")
public class ExecutorEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String appName;

    private String executorGroup;

    private String address;

    private ExecutorStatus status;

    private LocalDateTime lastHeartbeatTime;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}