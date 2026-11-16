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
 * 死信记录，对应 tp_dead_letter。
 * <p>第 4 周起有两种死信：</p>
 * <ul>
 *   <li>重试预算耗尽：任务反复失败，重试用完了，这条执行记录再也不会自己活过来；</li>
 *   <li>结果消息处理失败：消息进死信队列（DLX），毒消息留在队列里只会一直重投。</li>
 * </ul>
 * <p>{@code execution_id} 唯一：同一条执行记录只落一行死信，重复上报不会堆重复行。</p>
 */
@Getter
@Setter
@TableName("tp_dead_letter")
public class DeadLetterEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 执行记录 ID，唯一。 */
    private Long executionId;

    private Long taskId;

    private String taskName;

    private Integer shardIndex;

    private Integer shardTotal;

    private Integer retryCount;

    /** 最后一次失败原因。 */
    private String lastError;

    /** 进入死信的时间。 */
    private LocalDateTime deadTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
