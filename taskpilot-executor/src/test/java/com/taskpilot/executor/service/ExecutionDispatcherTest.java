package com.taskpilot.executor.service;

import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.metrics.MetricNames;
import com.taskpilot.common.mq.ResultChannel;
import com.taskpilot.executor.config.ExecutorProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionDispatcherTest {

    @Mock
    private TaskExecuteService taskExecuteService;

    @Mock
    private ResultChannel resultChannel;

    private ExecutorProperties properties;
    private ThreadPoolTaskExecutor dispatchExecutor;
    private ExecutionDispatcher dispatcher;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        properties = new ExecutorProperties();
        dispatchExecutor = new ThreadPoolTaskExecutor();
        dispatchExecutor.setCorePoolSize(2);
        dispatchExecutor.setMaxPoolSize(2);
        dispatchExecutor.setQueueCapacity(8);
        dispatchExecutor.setThreadNamePrefix("taskpilot-dispatch-");
        dispatchExecutor.initialize();
        dispatcher = new ExecutionDispatcher(dispatchExecutor, taskExecuteService, resultChannel, properties,
                meterRegistry);
    }

    @AfterEach
    void tearDown() {
        dispatchExecutor.shutdown();
    }

    @Test
    void acceptedRequestIsExecutedInBackgroundAndResultIsPublished() {
        // 第 4 周起：执行接口只回 ACK，结果由执行器跑完后经 MQ 回传。
        ExecuteRequest request = request();
        when(taskExecuteService.execute(request)).thenReturn(ExecuteResult.success("ok", 12));

        boolean accepted = dispatcher.dispatch(request);

        assertThat(accepted).isTrue();
        ArgumentCaptor<ExecutionResultMessage> captor = ArgumentCaptor.forClass(ExecutionResultMessage.class);
        verify(resultChannel, timeout(2000)).publish(captor.capture());
        ExecutionResultMessage message = captor.getValue();
        assertThat(message.executionId()).isEqualTo(100L);
        assertThat(message.taskId()).isEqualTo(1L);
        assertThat(message.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(message.durationMs()).isEqualTo(12L);
        assertThat(message.resultSummary()).isEqualTo("ok");
        assertThat(message.executorAddress()).isEqualTo(properties.getAddress());
        assertThat(message.reportedAt()).isNotNull();
    }

    @Test
    void failedExecutionIsReportedAsFailed() {
        ExecuteRequest request = request();
        when(taskExecuteService.execute(request))
                .thenReturn(ExecuteResult.failure("处理器抛异常", 30));

        assertThat(dispatcher.dispatch(request)).isTrue();

        ArgumentCaptor<ExecutionResultMessage> captor = ArgumentCaptor.forClass(ExecutionResultMessage.class);
        verify(resultChannel, timeout(2000)).publish(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(captor.getValue().errorMessage()).contains("抛异常");
    }

    @Test
    void timedOutExecutionIsReportedAsTimeout() {
        // 超时由执行器判定并上报，调度中心不再靠读超时猜。
        ExecuteRequest request = request();
        when(taskExecuteService.execute(request))
                .thenReturn(ExecuteResult.timeout("执行超时（5s）", 5000));

        assertThat(dispatcher.dispatch(request)).isTrue();

        ArgumentCaptor<ExecutionResultMessage> captor = ArgumentCaptor.forClass(ExecutionResultMessage.class);
        verify(resultChannel, timeout(2000)).publish(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ExecutionStatus.TIMEOUT);
    }

    @Test
    void fullDispatchPoolRejectsTheRequest() {
        // 调度线程池满了：直接拒绝，调度中心据此标失败并重试。
        ThreadPoolTaskExecutor rejecting = mock(ThreadPoolTaskExecutor.class);
        doThrow(new TaskRejectedException("dispatch pool is full"))
                .when(rejecting).execute(any(Runnable.class));
        ExecutionDispatcher service = new ExecutionDispatcher(rejecting, taskExecuteService, resultChannel,
                properties, meterRegistry);

        assertThat(service.dispatch(request())).isFalse();
        verify(resultChannel, never()).publish(any());
    }

    @Test
    void executionMetricsCountCompletionsWithHandlerTag() {
        // 4.10.4：执行完成计数与处理器耗时（低基数 handler 标签，ADR-022）。
        // 池满拒绝由 taskpilot.pool.rejected{pool="executionDispatchPool"} 计数（§2 装饰器），这里不重复。
        ExecuteRequest request = request();
        when(taskExecuteService.execute(request)).thenReturn(ExecuteResult.success("ok", 12));

        assertThat(dispatcher.dispatch(request)).isTrue();

        verify(resultChannel, timeout(2000)).publish(any());
        assertThat(meterRegistry.get(MetricNames.EXECUTIONS)
                .tag("status", "success").tag("handler", "demoHandler").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get(MetricNames.EXECUTION_DURATION)
                .tag("handler", "demoHandler").timer().count()).isEqualTo(1);
    }

    private ExecuteRequest request() {
        return new ExecuteRequest(100L, 1L, "demoHandler", "{}", 0, 1, 30);
    }
}
