package com.taskpilot.scheduler.dto;

import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerResultTest {

    @Test
    void runningExecutionsAreSummarizedAsRunning() {
        // 第 4 周起触发是异步的：接口返回时记录还在 RUNNING，
        // 汇总成 SUCCESS 会让调用方以为任务已经跑完。
        ExecutionLogEntity first = execution(1L, ExecutionStatus.RUNNING);
        ExecutionLogEntity second = execution(2L, ExecutionStatus.RUNNING);

        TriggerResult result = TriggerResult.of(List.of(first, second));

        assertThat(result.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(result.executionId()).isEqualTo(1L);
        assertThat(result.shardTotal()).isEqualTo(1);
    }

    @Test
    void failureWinsOverRunning() {
        ExecutionLogEntity running = execution(1L, ExecutionStatus.RUNNING);
        ExecutionLogEntity failed = execution(2L, ExecutionStatus.FAILED);

        TriggerResult result = TriggerResult.of(List.of(running, failed));

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void successIsReportedWhenAllExecutionsFinished() {
        ExecutionLogEntity first = execution(1L, ExecutionStatus.SUCCESS);
        ExecutionLogEntity second = execution(2L, ExecutionStatus.SUCCESS);

        TriggerResult result = TriggerResult.of(List.of(first, second));

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    private ExecutionLogEntity execution(Long id, ExecutionStatus status) {
        ExecutionLogEntity execution = new ExecutionLogEntity();
        execution.setId(id);
        execution.setShardIndex(0);
        execution.setShardTotal(1);
        execution.setStatus(status);
        return execution;
    }
}
