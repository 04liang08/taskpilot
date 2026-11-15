package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.dto.CreatedExecution;
import com.taskpilot.scheduler.leader.LeaderContext;
import com.taskpilot.scheduler.mapper.ExecutionLogMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionServiceTest {

    @Mock
    private ExecutionLogMapper executionLogMapper;

    @InjectMocks
    private ExecutionService executionService;

    @BeforeEach
    void initTableInfo() {
        // 纯 Mockito 单测没有 MyBatis 会话，手动初始化 Lambda 字段缓存。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "ExecutionLogEntity");
        TableInfoHelper.initTableInfo(assistant, ExecutionLogEntity.class);
    }

    @Test
    void createRunningReturnsExistingRecordWhenKeyIsDuplicated() {
        // 幂等键已存在：说明这次触发是重复的，不应该再执行一遍。
        when(executionLogMapper.insert(any(ExecutionLogEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate execution_key"));
        ExecutionLogEntity existing = new ExecutionLogEntity();
        existing.setId(200L);
        existing.setExecutionKey("key-1");
        when(executionLogMapper.selectOne(any())).thenReturn(existing);

        CreatedExecution result = executionService.createRunning(task(), executor(), TriggerType.MANUAL,
                LeaderContext.none(), 0, 1, "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.entity().getId()).isEqualTo(200L);
    }

    @Test
    void createRunningPersistsRunningRecordWithUuid() {
        when(executionLogMapper.insert(any(ExecutionLogEntity.class))).thenAnswer(invocation -> {
            ((ExecutionLogEntity) invocation.getArgument(0)).setId(100L);
            return 1;
        });

        CreatedExecution created = executionService.createRunning(task(), executor(), TriggerType.MANUAL,
                LeaderContext.none(), 0, 1, "key-1");
        ExecutionLogEntity execution = created.entity();

        assertThat(execution.getId()).isEqualTo(100L);
        assertThat(execution.getExecutionKey()).isNotBlank();
        assertThat(execution.getTriggerType()).isEqualTo(TriggerType.MANUAL);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(execution.getExecutorId()).isEqualTo(10L);
        assertThat(execution.getExecutorAddress()).isEqualTo("127.0.0.1:8081");
        assertThat(execution.getStartTime()).isNotNull();
    }

    @Test
    void completeSuccessMarksSuccess() {
        ExecutionLogEntity execution = runningExecution(101L);
        stubUpdateAndReload(execution);

        ExecutionLogEntity result = executionService.complete(execution,
                ExecuteResult.success("demoHandler done", 12));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getDurationMs()).isEqualTo(12);
        assertThat(result.getResultSummary()).isEqualTo("demoHandler done");
        assertThat(result.getEndTime()).isNotNull();
        verify(executionLogMapper).updateById(execution);
    }

    @Test
    void completeFailureMarksFailed() {
        ExecutionLogEntity execution = runningExecution(102L);
        stubUpdateAndReload(execution);

        ExecutionLogEntity result = executionService.complete(execution,
                ExecuteResult.failure("处理器执行失败", 8));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("处理器执行失败");
        assertThat(result.getDurationMs()).isEqualTo(8);
    }

    @Test
    void completeTimeoutMarksTimeout() {
        ExecutionLogEntity execution = runningExecution(103L);
        stubUpdateAndReload(execution);

        ExecutionLogEntity result = executionService.complete(execution,
                ExecuteResult.timeout("执行超时（5s）", 5003));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(result.getErrorMessage()).isEqualTo("执行超时（5s）");
    }

    @Test
    void markFailedAfterConnectionErrorKeepsRecord() {
        ExecutionLogEntity execution = runningExecution(104L);
        stubUpdateAndReload(execution);

        ExecutionLogEntity result = executionService.markFailed(execution, "调用执行器失败: Connection refused");

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("Connection refused");
        assertThat(result.getEndTime()).isNotNull();
    }

    @Test
    void createFailedPersistsFailedRecordWithoutExecutor() {
        ArgumentCaptor<ExecutionLogEntity> captor = ArgumentCaptor.forClass(ExecutionLogEntity.class);
        when(executionLogMapper.insert(any(ExecutionLogEntity.class))).thenAnswer(invocation -> {
            ((ExecutionLogEntity) invocation.getArgument(0)).setId(105L);
            return 1;
        });

        ExecutionLogEntity execution = executionService.createFailed(task(), "没有可用的在线执行器", LeaderContext.none());

        verify(executionLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("没有可用的在线执行器");
        assertThat(captor.getValue().getExecutorId()).isNull();
        assertThat(execution.getId()).isEqualTo(105L);
    }

    @Test
    void releaseRetryClaimPutsRecordBackToFailedSoFallbackScanCanRetry() {
        // 分片池满导致重试没发出去：不能把记录卡在 RUNNING，也不能白白消耗重试预算。
        ExecutionLogEntity execution = runningExecution(106L);
        execution.setRetryCount(1);

        executionService.releaseRetryClaim(execution,
                LocalDateTime.now().plusSeconds(5), "分片执行线程池已满，等待重试");

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getNextRetryTime()).isNotNull();
        assertThat(execution.getErrorMessage()).contains("线程池已满");
        // 这次根本没跑成，重试预算要还回去。
        assertThat(execution.getRetryCount()).isEqualTo(0);
        verify(executionLogMapper).update(isNull(), any());
    }

    @Test
    void failRunningOfExecutorSchedulesCronRecordsForRetry() {
        // 下线收尾不能只把 RUNNING 改成 FAILED：还要挂上 next_retry_time，
        // 转移/退避扫描才有得捞；MANUAL 记录保持终态（手动触发失败就是失败）。
        when(executionLogMapper.update(isNull(), any())).thenReturn(1);

        int affected = executionService.failRunningOfExecutor(10L, "执行器下线: 127.0.0.1:8081");

        assertThat(affected).isEqualTo(1);
        LambdaUpdateWrapper<ExecutionLogEntity> wrapper = captureUpdate();
        assertThat(wrapper.getSqlSet()).contains("status", "end_time", "error_message");
        assertThat(wrapper.getSqlSet())
                .contains("next_retry_time = CASE WHEN trigger_type = 'CRON' THEN NOW(3) ELSE NULL END");
        assertThat(wrapper.getSqlSegment()).contains("executor_id");
    }

    @Test
    void markRunningForRetryFinalizesRunningRecordAsFallback() {
        // 转移抢在收尾之前时 RUNNING 记录抢不动 claim，这里兜底把它收尾并挂上重试时间。
        when(executionLogMapper.update(isNull(), any())).thenReturn(1);

        executionService.markRunningForRetry(107L, "执行器下线: 等待重试");

        LambdaUpdateWrapper<ExecutionLogEntity> wrapper = captureUpdate();
        assertThat(wrapper.getSqlSet()).contains("status", "next_retry_time");
        // 带状态前置条件：只有还停在 RUNNING 的记录才会被这次兜底收尾。
        assertThat(wrapper.getSqlSegment()).contains("id", "status");
    }

    @Test
    void markRetryExhaustedClearsRetryTimeSoRecordIsTerminal() {
        ExecutionLogEntity execution = runningExecution(108L);
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setNextRetryTime(LocalDateTime.now());

        executionService.markRetryExhausted(execution);

        assertThat(execution.getNextRetryTime()).isNull();
        LambdaUpdateWrapper<ExecutionLogEntity> wrapper = captureUpdate();
        assertThat(wrapper.getSqlSet()).contains("next_retry_time");
        assertThat(wrapper.getSqlSegment()).contains("status");
    }

    @Test
    void listPendingRetriesOnlyPicksCronRecords() {
        // 手动触发失败就是失败：兜底扫描不能把 MANUAL 记录也捞回来重试。
        when(executionLogMapper.selectList(any())).thenReturn(List.of());

        assertThat(executionService.listPendingRetries()).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ExecutionLogEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(executionLogMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("trigger_type", "next_retry_time");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(TriggerType.CRON);
    }

    @Test
    void claimForRetryOnFailoverRebindsPersistedExecutor() {
        // 失败转移：抢占重试的同一条 UPDATE 把记录改指向目标执行器，
        // 转移成功后落终态不会再写回那台已经挂掉的旧执行器。
        ExecutionLogEntity execution = failedExecution(109L);
        ExecutorEntity target = new ExecutorEntity();
        target.setId(11L);
        target.setAddress("127.0.0.1:8082");
        when(executionLogMapper.claimForRetryOn(109L, 11L, "127.0.0.1:8082")).thenReturn(1);

        assertThat(executionService.claimForRetry(execution, target)).isTrue();

        verify(executionLogMapper).claimForRetryOn(109L, 11L, "127.0.0.1:8082");
        assertThat(execution.getExecutorId()).isEqualTo(11L);
        assertThat(execution.getExecutorAddress()).isEqualTo("127.0.0.1:8082");
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(execution.getRetryCount()).isEqualTo(1);
    }

    @Test
    void claimForRetryOnOriginalExecutorLeavesExecutorColumnsUntouched() {
        // 原地退避重试没有换机，executor 列必须保持原样。
        ExecutionLogEntity execution = failedExecution(110L);
        when(executionLogMapper.claimForRetry(110L)).thenReturn(1);

        assertThat(executionService.claimForRetry(execution)).isTrue();

        verify(executionLogMapper).claimForRetry(110L);
        verify(executionLogMapper, never()).claimForRetryOn(anyLong(), any(), any());
        assertThat(execution.getExecutorId()).isEqualTo(10L);
        assertThat(execution.getExecutorAddress()).isEqualTo("127.0.0.1:8081");
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<ExecutionLogEntity> captureUpdate() {
        ArgumentCaptor<Wrapper<ExecutionLogEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(executionLogMapper).update(isNull(), captor.capture());
        return (LambdaUpdateWrapper<ExecutionLogEntity>) captor.getValue();
    }

    private void stubUpdateAndReload(ExecutionLogEntity execution) {
        when(executionLogMapper.updateById(execution)).thenReturn(1);
        when(executionLogMapper.selectById(execution.getId())).thenReturn(execution);
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity();
        task.setId(1L);
        task.setTaskName("demo-task");
        task.setHandlerName("demoHandler");
        task.setHandlerParam("{\"message\":\"hi\"}");
        task.setShardTotal(1);
        task.setTimeoutSeconds(30);
        return task;
    }

    private ExecutorEntity executor() {
        ExecutorEntity executor = new ExecutorEntity();
        executor.setId(10L);
        executor.setAddress("127.0.0.1:8081");
        return executor;
    }

    private ExecutionLogEntity failedExecution(Long id) {
        ExecutionLogEntity execution = runningExecution(id);
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setRetryCount(0);
        execution.setExecutorId(10L);
        execution.setExecutorAddress("127.0.0.1:8081");
        execution.setNextRetryTime(LocalDateTime.now().minusSeconds(1));
        return execution;
    }

    private ExecutionLogEntity runningExecution(Long id) {
        ExecutionLogEntity execution = new ExecutionLogEntity();
        execution.setId(id);
        execution.setTaskId(1L);
        execution.setTaskName("demo-task");
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setStartTime(LocalDateTime.now().minusSeconds(1));
        return execution;
    }
}
