package com.taskpilot.scheduler.executor;

import com.taskpilot.scheduler.service.ExecutionService;
import com.taskpilot.scheduler.shard.ShardFailoverService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutorStatusListenerTest {

    @Mock
    private ExecutionService executionService;

    @InjectMocks
    private ExecutorStatusListener listener;

    @Test
    void executorOfflineFailsItsRunningExecutions() {
        when(executionService.failRunningOfExecutor(10L, "执行器下线: 127.0.0.1:8081")).thenReturn(2);

        listener.onStatusChanged(new ExecutorStatusChangedEvent(10L, "127.0.0.1:8081", false));

        verify(executionService).failRunningOfExecutor(10L, "执行器下线: 127.0.0.1:8081");
    }

    @Test
    void executorOnlineDoesNotTouchRunningExecutions() {
        listener.onStatusChanged(new ExecutorStatusChangedEvent(10L, "127.0.0.1:8081", true));

        verify(executionService, never()).failRunningOfExecutor(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void offlineFinalizationIsOrderedBeforeFailover() throws Exception {
        // 两个监听器听同一个下线事件：必须先收尾（RUNNING → FAILED）再转移。
        // 没有显式 @Order 时 Bean 加载顺序不确定，failover 抢在前面会让 RUNNING 记录永远丢掉。
        Method finalizeMethod = ExecutorStatusListener.class
                .getMethod("onStatusChanged", ExecutorStatusChangedEvent.class);
        Method failoverMethod = ShardFailoverService.class
                .getMethod("onExecutorStatusChanged", ExecutorStatusChangedEvent.class);

        Order finalizeOrder = AnnotationUtils.findAnnotation(finalizeMethod, Order.class);
        Order failoverOrder = AnnotationUtils.findAnnotation(failoverMethod, Order.class);

        assertThat(finalizeOrder).isNotNull();
        assertThat(failoverOrder).isNotNull();
        assertThat(finalizeOrder.value()).isLessThan(failoverOrder.value());
    }
}
