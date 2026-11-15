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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
        when(executionService.claimForRetry(execution)).thenReturn(true);

        service.failover(execution);

        verify(executionService).claimForRetry(execution);
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
        verify(executionService, never()).claimForRetry(any());
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
        when(executionService.claimForRetry(execution)).thenReturn(false);

        service.failover(execution);

        verify(triggerService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void failoverSkippedWhenNoOtherExecutorOnline() {
        ExecutionLogEntity execution = failedExecution(1L, 10L);
        when(taskService.get(1L)).thenReturn(task());
        when(executorService.listOnline()).thenReturn(List.of());

        service.failover(execution);

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
