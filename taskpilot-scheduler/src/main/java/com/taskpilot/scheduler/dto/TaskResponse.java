package com.taskpilot.scheduler.dto;

import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.scheduler.entity.TaskEntity;

import java.time.LocalDateTime;

public record TaskResponse(
        Long id,
        String taskName,
        String taskGroup,
        String cronExpression,
        TriggerType triggerType,
        String handlerName,
        String handlerParam,
        Integer shardTotal,
        Integer timeoutSeconds,
        Integer retryMax,
        Integer retryIntervalSeconds,
        TaskStatus status,
        LocalDateTime nextTriggerTime,
        LocalDateTime lastTriggerTime,
        String description,
        Integer version,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    public static TaskResponse from(TaskEntity entity) {
        return new TaskResponse(
                entity.getId(),
                entity.getTaskName(),
                entity.getTaskGroup(),
                entity.getCronExpression(),
                entity.getTriggerType(),
                entity.getHandlerName(),
                entity.getHandlerParam(),
                entity.getShardTotal(),
                entity.getTimeoutSeconds(),
                entity.getRetryMax(),
                entity.getRetryIntervalSeconds(),
                entity.getStatus(),
                entity.getNextTriggerTime(),
                entity.getLastTriggerTime(),
                entity.getDescription(),
                entity.getVersion(),
                entity.getCreateTime(),
                entity.getUpdateTime()
        );
    }
}
