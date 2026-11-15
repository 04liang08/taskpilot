package com.taskpilot.scheduler.executor;

import com.taskpilot.scheduler.service.ExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
