package com.taskpilot.scheduler.job;

import com.taskpilot.scheduler.mapper.ExecutionLogMapper;
import org.apache.ibatis.annotations.Update;
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

    @Test
    void staleRunningRecordsAreMarkedTimeoutAndScheduledForRetry() throws Exception {
        // 僵尸回收是"结果消息丢了"的最后防线：
        // 标 TIMEOUT（不是 FAILED），并给定时触发的记录挂上 next_retry_time，
        // 让退避扫描 / 失败转移接手重试。
        Update update = ExecutionLogMapper.class
                .getMethod("recoverStaleRunning", int.class)
                .getAnnotation(Update.class);
        assertThat(update).isNotNull();
        String sql = String.join(" ", update.value());

        assertThat(sql).contains("e.status = 'TIMEOUT'");
        assertThat(sql)
                .contains("next_retry_time = CASE WHEN e.trigger_type = 'CRON' THEN NOW(3) ELSE NULL END");
        // 只处理还停在 RUNNING 的记录。
        assertThat(sql).contains("e.status = 'RUNNING'");
    }
}
