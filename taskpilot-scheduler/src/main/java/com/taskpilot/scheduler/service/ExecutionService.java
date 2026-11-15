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
import com.taskpilot.scheduler.dto.CreatedExecution;
import com.taskpilot.scheduler.dto.ExecutionQuery;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.leader.LeaderContext;
import com.taskpilot.scheduler.mapper.ExecutionLogMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 执行记录持久化与状态流转：RUNNING -> SUCCESS / FAILED / TIMEOUT。
 * <p>先落 RUNNING 再调用执行器，保证超时或进程崩溃时仍有记录可查。</p>
 */
@Service
public class ExecutionService {

    private static final int RESULT_SUMMARY_MAX_LENGTH = 1024;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 4000;

    /**
     * 只给定时触发的记录挂上重试时间：手动触发失败就是失败，不做自动重试。
     */
    private static final String NEXT_RETRY_TIME_FOR_CRON =
            "next_retry_time = CASE WHEN trigger_type = 'CRON' THEN NOW(3) ELSE NULL END";

    private final ExecutionLogMapper executionLogMapper;

    public ExecutionService(ExecutionLogMapper executionLogMapper) {
        this.executionLogMapper = executionLogMapper;
    }

    /**
     * 落 RUNNING 记录。
     * <p>executionKey 是幂等键：手动触发用 UUID，定时触发用确定性键。
     * 同一个幂等键已经落过库时返回已有记录且 {@code created=false}，调用方据此跳过重复执行。</p>
     */
    public CreatedExecution createRunning(TaskEntity task,
                                          ExecutorEntity executor,
                                          TriggerType triggerType,
                                          LeaderContext leaderContext,
                                          int shardIndex,
                                          int shardTotal,
                                          String executionKey) {
        LocalDateTime now = LocalDateTime.now();
        ExecutionLogEntity entity = new ExecutionLogEntity();
        entity.setExecutionKey(executionKey);
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
        try {
            executionLogMapper.insert(entity);
            return new CreatedExecution(entity, true);
        } catch (DuplicateKeyException e) {
            // 幂等键已存在：说明这次触发是重复的，直接复用已有记录，不再执行一遍。
            ExecutionLogEntity existing = executionLogMapper.selectOne(
                    Wrappers.<ExecutionLogEntity>lambdaQuery()
                            .eq(ExecutionLogEntity::getExecutionKey, executionKey));
            if (existing != null) {
                return new CreatedExecution(existing, false);
            }
            throw e;
        }
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
     * <p>定时触发的记录顺带挂上重试时间：收尾之后由失败转移换机重试，
     * 万一转移也做不成，退避扫描还能把它捞回来——不能有"丢失路径"。</p>
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
                .set(ExecutionLogEntity::getErrorMessage, reason)
                .setSql(NEXT_RETRY_TIME_FOR_CRON));
    }

    /**
     * 把还 RUNNING 的记录收尾成 FAILED 并挂上重试时间，交给退避扫描/失败转移接手。
     * <p>用于收尾事件没来得及处理的兜底场景：记录必须离开 RUNNING，
     * 否则它永远抢不到重试，也就再也不会被处理。</p>
     */
    public void markRunningForRetry(Long executionId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        executionLogMapper.update(null, Wrappers.<ExecutionLogEntity>lambdaUpdate()
                .eq(ExecutionLogEntity::getId, executionId)
                .eq(ExecutionLogEntity::getStatus, ExecutionStatus.RUNNING)
                .set(ExecutionLogEntity::getStatus, ExecutionStatus.FAILED)
                .set(ExecutionLogEntity::getEndTime, now)
                .set(ExecutionLogEntity::getErrorMessage, truncate(reason, ERROR_MESSAGE_MAX_LENGTH))
                .setSql(NEXT_RETRY_TIME_FOR_CRON));
    }

    /**
     * 重试预算耗尽：清掉下次重试时间，这条记录就是终态（死信）了。
     */
    public void markRetryExhausted(ExecutionLogEntity execution) {
        executionLogMapper.update(null, Wrappers.<ExecutionLogEntity>lambdaUpdate()
                .eq(ExecutionLogEntity::getId, execution.getId())
                .in(ExecutionLogEntity::getStatus, ExecutionStatus.FAILED, ExecutionStatus.TIMEOUT)
                .set(ExecutionLogEntity::getNextRetryTime, null));
        execution.setNextRetryTime(null);
    }

    /**
     * 抢占一次重试：原子地把记录从 FAILED/TIMEOUT 改成 RUNNING。
     * <p>只有抢到的一方才应该真正重试，避免同一个分片被执行两遍。</p>
     *
     * @return true 表示抢占成功
     */
    public boolean claimForRetry(ExecutionLogEntity execution) {
        int updated = executionLogMapper.claimForRetry(execution.getId());
        if (updated == 0) {
            return false;
        }
        // 同步内存状态：后面的 updateById 会把整个实体写回去，不能用旧值覆盖 retry_count。
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setRetryCount(execution.getRetryCount() == null ? 1 : execution.getRetryCount() + 1);
        execution.setStartTime(LocalDateTime.now());
        execution.setEndTime(null);
        execution.setErrorMessage(null);
        execution.setNextRetryTime(null);
        return true;
    }

    /**
     * 某个执行器上还没结束的执行记录（RUNNING / FAILED / TIMEOUT）。
     * <p>执行器下线时用它找出需要转移的分片。</p>
     */
    public List<ExecutionLogEntity> listUnfinishedOfExecutor(Long executorId) {
        return executionLogMapper.selectList(Wrappers.<ExecutionLogEntity>lambdaQuery()
                .eq(ExecutionLogEntity::getExecutorId, executorId)
                .in(ExecutionLogEntity::getStatus,
                        ExecutionStatus.RUNNING, ExecutionStatus.FAILED, ExecutionStatus.TIMEOUT));
    }

    /**
     * 失败并安排退避重试。
     * <p>nextRetryTime 为空表示这次失败就是终态（不再重试）。</p>
     */
    public ExecutionLogEntity markFailedForRetry(ExecutionLogEntity execution,
                                                 String errorMessage,
                                                 LocalDateTime nextRetryTime) {
        LocalDateTime now = LocalDateTime.now();
        long durationMs = elapsedMs(execution.getStartTime());
        executionLogMapper.update(null, Wrappers.<ExecutionLogEntity>lambdaUpdate()
                .eq(ExecutionLogEntity::getId, execution.getId())
                .set(ExecutionLogEntity::getStatus, ExecutionStatus.FAILED)
                .set(ExecutionLogEntity::getEndTime, now)
                .set(ExecutionLogEntity::getDurationMs, durationMs)
                .set(ExecutionLogEntity::getErrorMessage, truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH))
                .set(ExecutionLogEntity::getNextRetryTime, nextRetryTime));
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setEndTime(now);
        execution.setDurationMs(durationMs);
        execution.setErrorMessage(truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH));
        execution.setNextRetryTime(nextRetryTime);
        return get(execution.getId());
    }

    /**
     * 抢占成功但没能真正执行（分片线程池满了等）：把记录放回 FAILED 并保留下次重试时间，
     * 让兜底扫描能重新捞回来。
     * <p>绝不能把记录留在 RUNNING——那意味着没人再管它，只能等僵尸回收超时才收尾。
     * 重试预算也要还回去：这次根本没跑成，不该消耗一次重试机会。</p>
     */
    public void releaseRetryClaim(ExecutionLogEntity execution,
                                  LocalDateTime nextRetryTime,
                                  String reason) {
        LocalDateTime now = LocalDateTime.now();
        executionLogMapper.update(null, Wrappers.<ExecutionLogEntity>lambdaUpdate()
                .eq(ExecutionLogEntity::getId, execution.getId())
                .eq(ExecutionLogEntity::getStatus, ExecutionStatus.RUNNING)
                .set(ExecutionLogEntity::getStatus, ExecutionStatus.FAILED)
                .set(ExecutionLogEntity::getEndTime, now)
                .set(ExecutionLogEntity::getErrorMessage, truncate(reason, ERROR_MESSAGE_MAX_LENGTH))
                .set(ExecutionLogEntity::getNextRetryTime, nextRetryTime)
                .setSql("retry_count = GREATEST(retry_count - 1, 0)"));
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setEndTime(now);
        execution.setErrorMessage(reason);
        execution.setNextRetryTime(nextRetryTime);
        if (execution.getRetryCount() != null && execution.getRetryCount() > 0) {
            execution.setRetryCount(execution.getRetryCount() - 1);
        }
    }

    /**
     * 还能不能再重试：已重试次数 < 任务配置的最大重试次数。
     */
    public boolean canRetry(ExecutionLogEntity execution, TaskEntity task) {
        int max = task.getRetryMax() == null ? 0 : task.getRetryMax();
        int done = execution.getRetryCount() == null ? 0 : execution.getRetryCount();
        return done < max;
    }

    /**
     * 到期需要退避重试的记录（next_retry_time 已到）。
     * <p>这是内存延迟队列的兜底：调度中心重启后，待重试的记录靠这里捞回来。</p>
     * <p>只捞定时触发的记录：手动触发失败就是失败，由用户决定要不要重来。</p>
     */
    public List<ExecutionLogEntity> listPendingRetries() {
        return executionLogMapper.selectList(Wrappers.<ExecutionLogEntity>lambdaQuery()
                .eq(ExecutionLogEntity::getStatus, ExecutionStatus.FAILED)
                .eq(ExecutionLogEntity::getTriggerType, TriggerType.CRON)
                .isNotNull(ExecutionLogEntity::getNextRetryTime)
                .le(ExecutionLogEntity::getNextRetryTime, LocalDateTime.now()));
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
