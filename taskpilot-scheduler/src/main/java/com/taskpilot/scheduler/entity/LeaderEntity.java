package com.taskpilot.scheduler.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 调度集群 leader 状态，对应 tp_leader（单行表，id 固定为 1）。
 */
@Getter
@Setter
@TableName("tp_leader")
public class LeaderEntity {

    @TableId(type = IdType.INPUT)
    private Integer id;

    private String instanceId;

    private Long fencingToken;

    private LocalDateTime leaderTime;

    private LocalDateTime fenceCheckTime;

    private Long fenceSeq;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
