package com.taskpilot.scheduler.shard;

import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.service.ExecutionService;
import com.taskpilot.scheduler.service.ExecutorService;
import com.taskpilot.scheduler.service.TaskService;
import com.taskpilot.scheduler.service.TriggerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShardFailoverServiceTest {

    @Mock
    private TaskService taskService;

    @Mock
    private ExecutorService executorService;

    @Mock
    private ExecutionService executionService;

    @Mock
    private TriggerService triggerService;

    @Mock
    private ShardAllocatorRegistry shardAllocatorRegistry;

    @Mock
    private ShardAllocator shardAllocator;

    private ShardFailoverService service;

    @BeforeEach
    void setUp() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setQueueCapacity(16);
        pool.initialize();
        service = new ShardFailoverService(taskService, executorService, executionService, triggerService,
                shardAllocatorRegistry, pool);
    }

    @Test
    void failoverReassignsShardToAnotherExecutorWhenOriginalIsOffline() {
        ExecutionLogEntity execution = failedExecution(1L, 10L);
        ExecutorEntity target = executor(11L, "127.0.0.1:8082");
        TaskEntity task = task();
        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        plan.put(0, List.of(target));

        when(taskService.get(1L)).thenReturn(task);
        // 在线列表里没有原执行器（已下线）。
        when(executorService.listOnline()).thenReturn(List.of(target));
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(shardAllocatorRegistry.current()).thenReturn(shardAllocator);
        when(shardAllocator.allocate(eq(1L), eq(2), anyList())).thenReturn(plan);
        when(executionService.claimForRetry(execution, target)).thenReturn(true);

        service.failover(execution);

        verify(executionService).claimForRetry(execution, target);
        verify(triggerService).dispatch(eq(execution), eq(task), eq(target), eq(TriggerType.CRON));
    }

    @Test
    void failoverSkippedWhenOriginalExecutorStillOnline() {
        ExecutionLogEntity execution = failedExecution(1L, 10L);
        ExecutorEntity original = executor(10L, "127.0.0.1:8081");
        ExecutorEntity other = executor(11L, "127.0.0.1:8082");
        when(taskService.get(1L)).thenReturn(task());
        when(executorService.listOnline()).thenReturn(List.of(original, other));

        service.failover(execution);

        // 执行器还活着：失败多半是任务本身的问题，不该转移。
        verify(executionService, never()).claimForRetry(any(), any());
        verify(triggerService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void failoverSkippedWhenRetryAlreadyClaimed() {
        ExecutionLogEntity execution = failedExecution(1L, 10L);
        TaskEntity task = task();
        ExecutorEntity target = executor(11L, "127.0.0.1:8082");
        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        plan.put(0, List.of(target));

        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(target));
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(shardAllocatorRegistry.current()).thenReturn(shardAllocator);
        when(shardAllocator.allocate(eq(1L), eq(2), anyList())).thenReturn(plan);
        when(executionService.claimForRetry(execution, target)).thenReturn(false);

        service.failover(execution);

        verify(triggerService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void failoverSkippedWhenNoOtherExecutorOnline() {
        ExecutionLogEntity execution = failedExecution(1L, 10L);
        TaskEntity task = task();
        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(executorService.listOnline()).thenReturn(List.of());

        service.failover(execution);

        verify(triggerService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void failoverDefersWhenRecordIsStillRunningSoNothingIsLost() {
        // 收尾事件没先跑（顺序异常）：RUNNING 的记录抢不动重试，
        // 必须手动收尾并挂上重试时间，交给退避扫描兜底，绝不能停在 RUNNING。
        ExecutionLogEntity execution = runningExecution(1L, 10L);
        when(taskService.get(1L)).thenReturn(task());

        service.failover(execution);

        verify(executionService).markRunningForRetry(eq(100L), anyString());
        verify(executionService, never()).claimForRetry(any(), any());
        verify(triggerService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void failoverRebindsTheRecordToTheTargetExecutorBeforeDispatch() {
        // 转移用的 claim 和 dispatch 必须指向同一台目标执行器，
        // 否则记录仍写着已下线的旧执行器，执行历史失真。
        ExecutionLogEntity execution = failedExecution(1L, 10L);
        TaskEntity task = task();
        ExecutorEntity target = executor(11L, "127.0.0.1:8082");
        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        plan.put(0, List.of(target));

        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(executorService.listOnline()).thenReturn(List.of(target));
        when(shardAllocatorRegistry.current()).thenReturn(shardAllocator);
        when(shardAllocator.allocate(eq(1L), eq(2), anyList())).thenReturn(plan);
        when(executionService.claimForRetry(execution, target)).thenReturn(true);

        service.failover(execution);

        ArgumentCaptor<ExecutorEntity> claimCaptor = ArgumentCaptor.forClass(ExecutorEntity.class);
        verify(executionService).claimForRetry(eq(execution), claimCaptor.capture());
        verify(triggerService).dispatch(eq(execution), eq(task), eq(target), eq(TriggerType.CRON));
        assertThat(claimCaptor.getValue().getId()).isEqualTo(target.getId());
        assertThat(claimCaptor.getValue().getAddress()).isEqualTo(target.getAddress());
    }

    @Test
    void failoverSkipsManualTriggerRecords() {
        // 手动触发失败就是失败：下线事件的批量转移不能把 MANUAL 记录也自动重试一遍。
        ExecutionLogEntity execution = failedExecution(1L, 10L);
        execution.setTriggerType(TriggerType.MANUAL);
        when(taskService.get(1L)).thenReturn(task());

        service.failover(execution);

        verify(executionService, never()).claimForRetry(any(), any());
        verify(triggerService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void failoverRetriesRecordFinalizedByTheOfflineListener() {
        // 完整链路：执行器下线 → 收尾把 RUNNING 变成 FAILED 并挂上 next_retry_time
        // → 转移用原子 claim 抢到它 → 换机重跑。有预算的 RUNNING 记录必须被重试。
        ExecutionLogEntity execution = failedExecution(1L, 10L);
        execution.setNextRetryTime(LocalDateTime.now().minusSeconds(1));
        TaskEntity task = task();
        ExecutorEntity target = executor(11L, "127.0.0.1:8082");
        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        plan.put(0, List.of(target));

        when(taskService.get(1L)).thenReturn(task);
        when(executionService.canRetry(execution, task)).thenReturn(true);
        when(executorService.listOnline()).thenReturn(List.of(target));
        when(shardAllocatorRegistry.current()).thenReturn(shardAllocator);
        when(shardAllocator.allocate(eq(1L), eq(2), anyList())).thenReturn(plan);
        when(executionService.claimForRetry(execution, target)).thenReturn(true);

        service.failover(execution);

        verify(executionService).claimForRetry(execution, target);
        verify(triggerService).dispatch(eq(execution), eq(task), eq(target), eq(TriggerType.CRON));
    }

    @Test
    void failoverMarksRecordTerminalWhenRetryBudgetExhausted() {
        // 死掉执行器上的记录没有重试预算：清掉 next_retry_time，当场落终态，不留丢失路径。
        ExecutionLogEntity execution = failedExecution(1L, 10L);
        execution.setNextRetryTime(LocalDateTime.now().minusSeconds(1));
        TaskEntity task = task();
        when(taskService.get(1L)).thenReturn(task);
        when(executorService.listOnline()).thenReturn(List.of(executor(11L, "127.0.0.1:8082")));
        when(executionService.canRetry(execution, task)).thenReturn(false);

        service.failover(execution);

        verify(executionService).markRetryExhausted(eq(execution), eq("重试预算耗尽"));
        verify(executionService, never()).claimForRetry(any(), any());
        verify(triggerService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void failoverMarksRecordTerminalWhenTaskIsDisabled() {
        // 任务被停用：没有重试的可能，也要落终态，不能让兜底扫描反复捞回来。
        ExecutionLogEntity execution = failedExecution(1L, 10L);
        TaskEntity task = task();
        task.setStatus(TaskStatus.DISABLED);
        when(taskService.get(1L)).thenReturn(task);

        service.failover(execution);

        verify(executionService).markRetryExhausted(eq(execution), eq("任务已停用或被删除，不再重试"));
        verify(triggerService, never()).dispatch(any(), any(), any(), any());
    }

    private ExecutionLogEntity failedExecution(Long taskId, Long executorId) {
        ExecutionLogEntity execution = new ExecutionLogEntity();
        execution.setId(100L);
        execution.setTaskId(taskId);
        execution.setTaskName("demo");
        execution.setTriggerType(TriggerType.CRON);
        execution.setExecutorId(executorId);
        execution.setExecutorAddress("127.0.0.1:8081");
        execution.setShardIndex(0);
        execution.setShardTotal(2);
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setRetryCount(0);
        return execution;
    }

    private ExecutionLogEntity runningExecution(Long taskId, Long executorId) {
        ExecutionLogEntity execution = new ExecutionLogEntity();
        execution.setId(100L);
        execution.setTaskId(taskId);
        execution.setTaskName("demo");
        execution.setTriggerType(TriggerType.CRON);
        execution.setExecutorId(executorId);
        execution.setExecutorAddress("127.0.0.1:8081");
        execution.setShardIndex(0);
        execution.setShardTotal(2);
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setRetryCount(0);
        return execution;
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity();
        task.setId(1L);
        task.setTaskName("demo");
        task.setStatus(TaskStatus.ENABLED);
        task.setHandlerName("demoHandler");
        task.setTimeoutSeconds(30);
        task.setShardTotal(2);
        return task;
    }

    private ExecutorEntity executor(Long id, String address) {
        ExecutorEntity executor = new ExecutorEntity();
        executor.setId(id);
        executor.setAddress(address);
        return executor;
    }
}
