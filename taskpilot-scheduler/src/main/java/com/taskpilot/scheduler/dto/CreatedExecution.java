package com.taskpilot.scheduler.dto;

import com.taskpilot.scheduler.entity.ExecutionLogEntity;

/**
 * 创建执行记录的结果。
 *
 * @param entity  执行记录（新建的或已存在的重复记录）
 * @param created true 表示这次是新建的；false 表示幂等键已存在，属于重复触发
 */
public record CreatedExecution(ExecutionLogEntity entity, boolean created) {
}
