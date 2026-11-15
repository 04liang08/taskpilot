package com.taskpilot.scheduler.job;

import com.taskpilot.scheduler.mapper.ExecutionLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionRecoveryJobTest {

    @Mock
    private ExecutionLogMapper executionLogMapper;

    private ExecutionRecoveryJob job;

    @BeforeEach
    void setUp() {
        // 宽限期是 @Value 注入的，Mockito 注不进来，手动构造。
        job = new ExecutionRecoveryJob(executionLogMapper, 60);
    }

    @Test
    void recoversStaleRunningRecords() {
        when(executionLogMapper.recoverStaleRunning(60)).thenReturn(2);

        job.recoverStaleRunning();

        verify(executionLogMapper).recoverStaleRunning(60);
    }

    @Test
    void keepsGoingWhenRecoveryFails() {
        when(executionLogMapper.recoverStaleRunning(60)).thenThrow(new RuntimeException("db down"));

        job.recoverStaleRunning();
    }
}
