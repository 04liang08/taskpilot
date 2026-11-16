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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private static final int RESULT_SUMMARY_MAX_LENGTH = 1024;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 4000;

    /**
     * 只给定时触发的记录挂上重试时间：手动触发失败就是失败，不做自动重试。
     */
    private static final String NEXT_RETRY_TIME_FOR_CRON =
            "next_retry_time = CASE WHEN trigger_type = 'CRON' THEN NOW(3) ELSE NULL END";

    private final ExecutionLogMapper executionLogMapper;
    private final DeadLetterService deadLetterService;

    public ExecutionService(ExecutionLogMapper executionLogMapper, DeadLetterService deadLetterService) {
        this.executionLogMapper = executionLogMapper;
        this.deadLetterService = deadLetterService;
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
     * <p>triggerType 必须用本次真实的触发方式：cron 任务没有在线执行器时
     * 记成 MANUAL 会让执行历史失真，也会被"只重试 CRON"的规则误判。</p>
     */
    public ExecutionLogEntity createFailed(TaskEntity task, String errorMessage,
                                           LeaderContext leaderContext, TriggerType triggerType) {
        LocalDateTime now = LocalDateTime.now();
        ExecutionLogEntity entity = new ExecutionLogEntity();
        entity.setExecutionKey(UUID.randomUUID().toString());
        entity.setTaskId(task.getId());
        entity.setTaskName(task.getTaskName());
        entity.setTriggerType(triggerType);
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
     * <p>顺带落一条死信记录并广播告警：这条执行记录再也不会自己活过来，
     * 需要有人来看一眼。死信按 execution_id 幂等，重复调用不会堆重复行。</p>
     */
    public void markRetryExhausted(ExecutionLogEntity execution) {
        markRetryExhausted(execution, "重试预算耗尽");
    }

    /**
     * 重试预算耗尽（或任务已停用）：清掉下次重试时间并落死信。
     *
     * @param reason 死信原因，会记进 tp_dead_letter.last_error
     */
    public void markRetryExhausted(ExecutionLogEntity execution, String reason) {
        executionLogMapper.update(null, Wrappers.<ExecutionLogEntity>lambdaUpdate()
                .eq(ExecutionLogEntity::getId, execution.getId())
                .in(ExecutionLogEntity::getStatus, ExecutionStatus.FAILED, ExecutionStatus.TIMEOUT)
                .set(ExecutionLogEntity::getNextRetryTime, null));
        execution.setNextRetryTime(null);
        deadLetterService.record(execution, reason);
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
        syncClaimedState(execution);
        return true;
    }

    /**
     * 失败转移抢占重试：在把记录改成 RUNNING 的同一条 UPDATE 里，把 executor 列
     * 一并改指向目标执行器。
     * <p>转移后 {@code dispatch()} 会复用这个实体落终态，如果 executor 字段还是旧的，
     * 执行记录会永久指向那台已经挂掉的执行器，历史失真。内存实体也要同步，
     * 否则后续任何整实体写回都可能把旧值覆盖回去。</p>
     */
    public boolean claimForRetry(ExecutionLogEntity execution, ExecutorEntity target) {
        int updated = executionLogMapper.claimForRetryOn(
                execution.getId(), target.getId(), target.getAddress());
        if (updated == 0) {
            return false;
        }
        syncClaimedState(execution);
        execution.setExecutorId(target.getId());
        execution.setExecutorAddress(target.getAddress());
        return true;
    }

    private void syncClaimedState(ExecutionLogEntity execution) {
        // 同步内存状态：后面的 dispatch / finish 会读这个实体，不能用旧值覆盖 retry_count。
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setRetryCount(execution.getRetryCount() == null ? 1 : execution.getRetryCount() + 1);
        execution.setStartTime(LocalDateTime.now());
        execution.setEndTime(null);
        execution.setErrorMessage(null);
        execution.setNextRetryTime(null);
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
     * <p>带状态前置条件（FAILED / TIMEOUT）：迟到的失败事件不能把记录覆写回 FAILED。
     * 失败转移已经用 {@code claimForRetryOn} 把记录抢成 RUNNING 时，
     * 这条记录归转移逻辑管，再挂一次 next_retry_time 会造成状态回退和重复重试。</p>
     */
    public ExecutionLogEntity markFailedForRetry(ExecutionLogEntity execution,
                                                 String errorMessage,
                                                 LocalDateTime nextRetryTime) {
        LocalDateTime now = LocalDateTime.now();
        long durationMs = elapsedMs(execution.getStartTime());
        executionLogMapper.update(null, Wrappers.<ExecutionLogEntity>lambdaUpdate()
                .eq(ExecutionLogEntity::getId, execution.getId())
                .in(ExecutionLogEntity::getStatus, ExecutionStatus.FAILED, ExecutionStatus.TIMEOUT)
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
     * <p>TIMEOUT 也要捞：结果消息丢失时，僵尸回收把记录标成 TIMEOUT 并挂了重试时间，
     * 靠这里接手重试。</p>
     */
    public List<ExecutionLogEntity> listPendingRetries() {
        return executionLogMapper.selectList(Wrappers.<ExecutionLogEntity>lambdaQuery()
                .in(ExecutionLogEntity::getStatus, ExecutionStatus.FAILED, ExecutionStatus.TIMEOUT)
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

    /**
     * 落终态，返回是否真的发生了状态迁移。
     * <p>{@code false} 表示记录已经不是 RUNNING 了（重复投递、已被下线收尾、
     * 已被其它路径收成终态）——这时不能再覆写终态列。</p>
     */
    public boolean finishIfRunning(ExecutionLogEntity execution,
                                   ExecutionStatus status,
                                   long durationMs,
                                   String errorMessage,
                                   String resultSummary) {
        LocalDateTime endTime = LocalDateTime.now();
        String error = truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH);
        String summary = truncate(resultSummary, RESULT_SUMMARY_MAX_LENGTH);
        // 只更新终态列：整实体 updateById 会把并发路径刚写进去的字段用旧值覆盖回去
        // （失败转移的 executor 列、claim 的 retry_count、收尾/退避的 next_retry_time）。
        // 带 status=RUNNING 前置条件：已经被下线收尾或别的路径改成终态的记录不再二次覆写。
        // 重复投递的结果消息也因此天然幂等。
        int updated = executionLogMapper.update(null, Wrappers.<ExecutionLogEntity>lambdaUpdate()
                .eq(ExecutionLogEntity::getId, execution.getId())
                .eq(ExecutionLogEntity::getStatus, ExecutionStatus.RUNNING)
                .set(ExecutionLogEntity::getStatus, status)
                .set(ExecutionLogEntity::getEndTime, endTime)
                .set(ExecutionLogEntity::getDurationMs, durationMs)
                .set(ExecutionLogEntity::getErrorMessage, error)
                .set(ExecutionLogEntity::getResultSummary, summary));
        if (updated == 0) {
            log.info("execution already finalized, skip terminal overwrite: executionId={}", execution.getId());
        }
        // 同步内存实体：调用方（结果日志、失败事件）要读终态。
        execution.setStatus(status);
        execution.setEndTime(endTime);
        execution.setDurationMs(durationMs);
        execution.setErrorMessage(error);
        execution.setResultSummary(summary);
        return updated > 0;
    }

    /**
     * 这个任务当前有没有还在跑的记录。
     * <p>第 4 周起触发是异步的（执行结果经 RabbitMQ 回传），"任务正在执行中"
     * 这件事不能再靠进程内的内存锁判断：集群里别的实例看不到那把锁，
     * 而且 doTrigger 秒回、锁立刻释放，防并发就形同虚设。查 DB 才是集群口径。</p>
     */
    public boolean existsRunning(Long taskId) {
        Long count = executionLogMapper.selectCount(Wrappers.<ExecutionLogEntity>lambdaQuery()
                .eq(ExecutionLogEntity::getTaskId, taskId)
                .eq(ExecutionLogEntity::getStatus, ExecutionStatus.RUNNING));
        return count != null && count > 0;
    }

    private ExecutionLogEntity finish(ExecutionLogEntity execution,
                                      ExecutionStatus status,
                                      long durationMs,
                                      String errorMessage,
                                      String resultSummary) {
        finishIfRunning(execution, status, durationMs, errorMessage, resultSummary);
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
