package com.taskpilot.scheduler.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskpilot.common.api.PageResult;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.scheduler.dto.TaskCreateRequest;
import com.taskpilot.scheduler.dto.CronValidateResult;
import com.taskpilot.scheduler.dto.TaskQuery;
import com.taskpilot.scheduler.dto.TaskUpdateRequest;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.mapper.TaskMapper;
import com.taskpilot.scheduler.support.CronService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 任务定义管理。第 1 周只做 CRUD/启停，定时触发在第 2 周接入。
 */
@Service
public class TaskService {

    private static final String DEFAULT_GROUP = "DEFAULT";

    private final TaskMapper taskMapper;
    private final CronService cronService;

    public TaskService(TaskMapper taskMapper, CronService cronService) {
        this.taskMapper = taskMapper;
        this.cronService = cronService;
    }

    @Transactional
    public TaskEntity create(TaskCreateRequest request) {
        String taskName = request.getTaskName().trim();
        String cronExpression = request.getCronExpression().trim();
        assertCronValid(cronExpression);
        checkNameUnique(taskName, null);

        TaskEntity entity = new TaskEntity();
        entity.setTaskName(taskName);
        entity.setTaskGroup(StringUtils.hasText(request.getTaskGroup())
                ? request.getTaskGroup().trim() : DEFAULT_GROUP);
        entity.setCronExpression(cronExpression);
        entity.setTriggerType(TriggerType.CRON);
        entity.setHandlerName(request.getHandlerName().trim());
        entity.setHandlerParam(request.getHandlerParam());
        entity.setShardTotal(defaultIfNull(request.getShardTotal(), 1));
        entity.setTimeoutSeconds(defaultIfNull(request.getTimeoutSeconds(), 60));
        entity.setRetryMax(defaultIfNull(request.getRetryMax(), 0));
        entity.setRetryIntervalSeconds(defaultIfNull(request.getRetryIntervalSeconds(), 10));
        entity.setStatus(TaskStatus.ENABLED);
        entity.setDescription(request.getDescription());

        taskMapper.insert(entity);
        return get(entity.getId());
    }

    @Transactional
    public TaskEntity update(Long id, TaskUpdateRequest request) {
        TaskEntity entity = get(id);
        if (request.getVersion() != null && !request.getVersion().equals(entity.getVersion())) {
            throw new BizException(ErrorCode.CONFLICT, "任务已被他人修改，请刷新后重试");
        }

        String taskName = request.getTaskName().trim();
        String cronExpression = request.getCronExpression().trim();
        assertCronValid(cronExpression);
        checkNameUnique(taskName, id);

        entity.setTaskName(taskName);
        entity.setTaskGroup(StringUtils.hasText(request.getTaskGroup())
                ? request.getTaskGroup().trim() : entity.getTaskGroup());
        entity.setCronExpression(cronExpression);
        entity.setHandlerName(request.getHandlerName().trim());
        // 可选字段为 null 时保持原值；避免实体被置空后响应与实际库中数据不一致。
        if (request.getHandlerParam() != null) {
            entity.setHandlerParam(request.getHandlerParam());
        }
        if (request.getShardTotal() != null) {
            entity.setShardTotal(request.getShardTotal());
        }
        if (request.getTimeoutSeconds() != null) {
            entity.setTimeoutSeconds(request.getTimeoutSeconds());
        }
        if (request.getRetryMax() != null) {
            entity.setRetryMax(request.getRetryMax());
        }
        if (request.getRetryIntervalSeconds() != null) {
            entity.setRetryIntervalSeconds(request.getRetryIntervalSeconds());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }

        updateWithVersionCheck(entity);
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        int rows = taskMapper.deleteById(id);
        if (rows == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在或已删除: " + id);
        }
    }

    public TaskEntity get(Long id) {
        TaskEntity entity = taskMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + id);
        }
        return entity;
    }

    public PageResult<TaskEntity> page(TaskQuery query) {
        Page<TaskEntity> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<TaskEntity> wrapper = Wrappers.<TaskEntity>lambdaQuery()
                .orderByDesc(TaskEntity::getId);
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(TaskEntity::getTaskName, query.getKeyword().trim());
        }
        if (query.getStatus() != null) {
            wrapper.eq(TaskEntity::getStatus, query.getStatus());
        }
        if (StringUtils.hasText(query.getGroup())) {
            wrapper.eq(TaskEntity::getTaskGroup, query.getGroup().trim());
        }

        Page<TaskEntity> result = taskMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public TaskEntity changeStatus(Long id, TaskStatus status) {
        TaskEntity entity = get(id);
        if (entity.getStatus() == status) {
            return entity;
        }
        entity.setStatus(status);
        updateWithVersionCheck(entity);
        return get(id);
    }

    /**
     * 记录上次触发时间。用定向更新而不是 updateById：不碰乐观锁版本，
     * 避免触发链路和用户在页面上编辑任务互相冲突。
     */
    public void markTriggered(Long id) {
        taskMapper.update(null, Wrappers.<TaskEntity>lambdaUpdate()
                .eq(TaskEntity::getId, id)
                .set(TaskEntity::getLastTriggerTime, LocalDateTime.now()));
    }

    private void updateWithVersionCheck(TaskEntity entity) {
        int rows = taskMapper.updateById(entity);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "任务已被他人修改，请刷新后重试");
        }
    }

    private void checkNameUnique(String taskName, Long excludeId) {
        LambdaQueryWrapper<TaskEntity> wrapper = Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getTaskName, taskName);
        if (excludeId != null) {
            wrapper.ne(TaskEntity::getId, excludeId);
        }
        Long count = taskMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.CONFLICT, "任务名称已存在: " + taskName);
        }
    }

    private void assertCronValid(String expression) {
        CronValidateResult result = cronService.validate(expression);
        if (!result.valid()) {
            throw new BizException(ErrorCode.BAD_REQUEST, result.message());
        }
    }

    private static int defaultIfNull(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }
}