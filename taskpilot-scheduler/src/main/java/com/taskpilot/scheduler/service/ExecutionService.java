package com.taskpilot.scheduler.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskpilot.common.api.PageResult;
import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.scheduler.dto.ExecutionQuery;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.leader.LeaderContext;
import com.taskpilot.scheduler.mapper.ExecutionLogMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 执行记录持久化与状态流转：RUNNING -> SUCCESS / FAILED / TIMEOUT。
 * <p>先落 RUNNING 再调用执行器，保证超时或进程崩溃时仍有记录可查。</p>
 */
@Service
public class ExecutionService {

    private static final int RESULT_SUMMARY_MAX_LENGTH = 1024;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 4000;

    private final ExecutionLogMapper executionLogMapper;

    public ExecutionService(ExecutionLogMapper executionLogMapper) {
        this.executionLogMapper = executionLogMapper;
    }

    /**
     * 落 RUNNING 记录。execution_key 使用 UUID，允许重复手动触发。
     */
    public ExecutionLogEntity createRunning(TaskEntity task,
                                            ExecutorEntity executor,
                                            TriggerType triggerType,
                                            LeaderContext leaderContext,
                                            int shardIndex,
                                            int shardTotal) {
        LocalDateTime now = LocalDateTime.now();
        ExecutionLogEntity entity = new ExecutionLogEntity();
        entity.setExecutionKey(UUID.randomUUID().toString());
        entity.setTaskId(task.getId());
        entity.setTaskName(task.getTaskName());
        entity.setTriggerType(triggerType);
        entity.setTriggerTime(now);
        entity.setStartTime(now);
        entity.setExecutorId(executor == null ? null : executor.getId());
        entity.setExecutorAddress(executor == null ? null : executor.getAddress());
        entity.setShardIndex(shardIndex);
        entity.setShardTotal(shardTotal);
        entity.setStatus(ExecutionStatus.RUNNING);
        entity.setRetryCount(0);
        entity.setFencingToken(leaderContext.fencingToken());
        entity.setLeaderInstance(leaderContext.instanceId());
        executionLogMapper.insert(entity);
        return entity;
    }

    /**
     * 调度前就失败（例如没有在线执行器），也保留一条 FAILED 记录，便于页面追溯。
     */
    public ExecutionLogEntity createFailed(TaskEntity task, String errorMessage, LeaderContext leaderContext) {
        LocalDateTime now = LocalDateTime.now();
        ExecutionLogEntity entity = new ExecutionLogEntity();
        entity.setExecutionKey(UUID.randomUUID().toString());
        entity.setTaskId(task.getId());
        entity.setTaskName(task.getTaskName());
        entity.setTriggerType(TriggerType.MANUAL);
        entity.setTriggerTime(now);
        entity.setStartTime(now);
        entity.setEndTime(now);
        entity.setShardIndex(0);
        entity.setShardTotal(task.getShardTotal() == null ? 1 : task.getShardTotal());
        entity.setStatus(ExecutionStatus.FAILED);
        entity.setRetryCount(0);
        entity.setFencingToken(leaderContext.fencingToken());
        entity.setLeaderInstance(leaderContext.instanceId());
        entity.setDurationMs(0L);
        entity.setErrorMessage(truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH));
        executionLogMapper.insert(entity);
        return entity;
    }

    /**
     * 按执行器返回结果落终态：超时 -> TIMEOUT；失败 -> FAILED；成功 -> SUCCESS。
     */
    public ExecutionLogEntity complete(ExecutionLogEntity execution, ExecuteResult result) {
        ExecutionStatus status;
        if (result.timeout()) {
            status = ExecutionStatus.TIMEOUT;
        } else if (result.success()) {
            status = ExecutionStatus.SUCCESS;
        } else {
            status = ExecutionStatus.FAILED;
        }
        long durationMs = result.durationMs() > 0
                ? result.durationMs()
                : elapsedMs(execution.getStartTime());
        return finish(execution, status, durationMs, result.errorMessage(), result.resultSummary());
    }

    public ExecutionLogEntity markFailed(ExecutionLogEntity execution, String errorMessage) {
        return finish(execution, ExecutionStatus.FAILED, elapsedMs(execution.getStartTime()),
                errorMessage, null);
    }

    public ExecutionLogEntity markTimeout(ExecutionLogEntity execution, String errorMessage) {
        return finish(execution, ExecutionStatus.TIMEOUT, elapsedMs(execution.getStartTime()),
                errorMessage, null);
    }

    public ExecutionLogEntity get(Long id) {
        ExecutionLogEntity entity = executionLogMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "执行记录不存在: " + id);
        }
        return entity;
    }

    /**
     * 执行器下线时，把它身上还处于 RUNNING 的执行记录标记为 FAILED。
     * <p>执行器没了，这些执行不可能再有结果；不收尾的话概览的"运行中"会一直挂着。</p>
     *
     * @return 受影响的记录数
     */
    public int failRunningOfExecutor(Long executorId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        return executionLogMapper.update(null, Wrappers.<ExecutionLogEntity>lambdaUpdate()
                .eq(ExecutionLogEntity::getExecutorId, executorId)
                .eq(ExecutionLogEntity::getStatus, ExecutionStatus.RUNNING)
                .set(ExecutionLogEntity::getStatus, ExecutionStatus.FAILED)
                .set(ExecutionLogEntity::getEndTime, now)
                .set(ExecutionLogEntity::getErrorMessage, reason));
    }

    public PageResult<ExecutionLogEntity> page(ExecutionQuery query) {
        Page<ExecutionLogEntity> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<ExecutionLogEntity> wrapper = Wrappers.<ExecutionLogEntity>lambdaQuery()
                .orderByDesc(ExecutionLogEntity::getTriggerTime)
                .orderByDesc(ExecutionLogEntity::getId);
        if (query.getTaskId() != null) {
            wrapper.eq(ExecutionLogEntity::getTaskId, query.getTaskId());
        }
        if (query.getStatus() != null) {
            wrapper.eq(ExecutionLogEntity::getStatus, query.getStatus());
        }
        if (query.getStartTime() != null) {
            wrapper.ge(ExecutionLogEntity::getTriggerTime, query.getStartTime());
        }
        if (query.getEndTime() != null) {
            wrapper.le(ExecutionLogEntity::getTriggerTime, query.getEndTime());
        }

        Page<ExecutionLogEntity> result = executionLogMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    private ExecutionLogEntity finish(ExecutionLogEntity execution,
                                      ExecutionStatus status,
                                      long durationMs,
                                      String errorMessage,
                                      String resultSummary) {
        execution.setStatus(status);
        execution.setEndTime(LocalDateTime.now());
        execution.setDurationMs(durationMs);
        execution.setErrorMessage(truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH));
        execution.setResultSummary(truncate(resultSummary, RESULT_SUMMARY_MAX_LENGTH));
        executionLogMapper.updateById(execution);
        return get(execution.getId());
    }

    private long elapsedMs(LocalDateTime startTime) {
        if (startTime == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(startTime, LocalDateTime.now()).toMillis());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
