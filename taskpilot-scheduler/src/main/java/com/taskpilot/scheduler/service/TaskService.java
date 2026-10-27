package com.taskpilot.scheduler.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskpilot.common.api.PageResult;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.scheduler.dto.TaskCreateRequest;
import com.taskpilot.scheduler.dto.TaskQuery;
import com.taskpilot.scheduler.dto.TaskResponse;
import com.taskpilot.scheduler.dto.TaskUpdateRequest;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class TaskService {

    private static final String DEFAULT_GROUP = "DEFAULT";

    private final TaskMapper taskMapper;

    public TaskService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Transactional
    public TaskResponse create(TaskCreateRequest request) {
        ensureNameAvailable(request.taskName(), null);

        TaskEntity entity = new TaskEntity();
        entity.setTaskName(request.taskName().trim());
        entity.setTaskGroup(defaultGroup(request.taskGroup()));
        entity.setCronExpression(request.cronExpression().trim());
        entity.setTriggerType(request.triggerType() == null ? TriggerType.CRON : request.triggerType());
        entity.setHandlerName(request.handlerName().trim());
        entity.setHandlerParam(request.handlerParam());
        entity.setShardTotal(request.shardTotal() == null ? 1 : request.shardTotal());
        entity.setTimeoutSeconds(request.timeoutSeconds() == null ? 60 : request.timeoutSeconds());
        entity.setRetryMax(request.retryMax() == null ? 0 : request.retryMax());
        entity.setRetryIntervalSeconds(request.retryIntervalSeconds() == null ? 10 : request.retryIntervalSeconds());
        entity.setStatus(TaskStatus.ENABLED);
        entity.setDescription(request.description());
        entity.setVersion(0);
        entity.setDeleted(0);

        taskMapper.insert(entity);
        return TaskResponse.from(taskMapper.selectById(entity.getId()));
    }

    @Transactional
    public TaskResponse update(Long id, TaskUpdateRequest request) {
        TaskEntity entity = getEntity(id);
        ensureNameAvailable(request.taskName(), id);

        entity.setTaskName(request.taskName().trim());
        entity.setTaskGroup(defaultGroup(request.taskGroup()));
        entity.setCronExpression(request.cronExpression().trim());
        if (request.triggerType() != null) {
            entity.setTriggerType(request.triggerType());
        }
        entity.setHandlerName(request.handlerName().trim());
        entity.setHandlerParam(request.handlerParam());
        entity.setShardTotal(request.shardTotal() == null ? 1 : request.shardTotal());
        entity.setTimeoutSeconds(request.timeoutSeconds() == null ? 60 : request.timeoutSeconds());
        entity.setRetryMax(request.retryMax() == null ? 0 : request.retryMax());
        entity.setRetryIntervalSeconds(request.retryIntervalSeconds() == null ? 10 : request.retryIntervalSeconds());
        entity.setDescription(request.description());

        if (taskMapper.updateById(entity) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "任务已被其他请求修改，请刷新后重试");
        }
        return TaskResponse.from(taskMapper.selectById(id));
    }

    @Transactional
    public void delete(Long id) {
        if (taskMapper.deleteById(id) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
    }

    public PageResult<TaskResponse> page(TaskQuery query) {
        Page<TaskEntity> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(TaskEntity::getTaskName, keyword)
                    .or()
                    .like(TaskEntity::getHandlerName, keyword));
        }
        if (query.getStatus() != null) {
            wrapper.eq(TaskEntity::getStatus, query.getStatus());
        }
        if (StringUtils.hasText(query.getGroup())) {
            wrapper.eq(TaskEntity::getTaskGroup, query.getGroup().trim());
        }
        wrapper.orderByDesc(TaskEntity::getId);

        Page<TaskEntity> result = taskMapper.selectPage(page, wrapper);
        List<TaskResponse> records = result.getRecords().stream()
                .map(TaskResponse::from)
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    public TaskResponse get(Long id) {
        return TaskResponse.from(getEntity(id));
    }

    @Transactional
    public TaskResponse enable(Long id) {
        return changeStatus(id, TaskStatus.ENABLED);
    }

    @Transactional
    public TaskResponse disable(Long id) {
        return changeStatus(id, TaskStatus.DISABLED);
    }

    private TaskResponse changeStatus(Long id, TaskStatus status) {
        TaskEntity entity = getEntity(id);
        entity.setStatus(status);
        if (taskMapper.updateById(entity) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "任务已被其他请求修改，请刷新后重试");
        }
        return TaskResponse.from(taskMapper.selectById(id));
    }

    private void ensureNameAvailable(String taskName, Long excludeId) {
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getTaskName, taskName.trim())
                .ne(excludeId != null, TaskEntity::getId, excludeId);
        if (taskMapper.selectCount(wrapper) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "任务名称已存在");
        }
    }

    private TaskEntity getEntity(Long id) {
        TaskEntity entity = taskMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return entity;
    }

    private String defaultGroup(String group) {
        return StringUtils.hasText(group) ? group.trim() : DEFAULT_GROUP;
    }
}
