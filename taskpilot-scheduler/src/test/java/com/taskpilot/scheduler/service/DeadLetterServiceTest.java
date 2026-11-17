package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.AlertMessage;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.metrics.MetricNames;
import com.taskpilot.common.mq.AlertChannel;
import com.taskpilot.scheduler.entity.DeadLetterEntity;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.mapper.DeadLetterMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceTest {

    @Mock
    private DeadLetterMapper deadLetterMapper;

    @Mock
    private AlertChannel alertChannel;

    private DeadLetterService deadLetterService;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        deadLetterService = new DeadLetterService(deadLetterMapper, alertChannel, meterRegistry);
    }

    @Test
    void recordsDeadLetterAndRaisesAlert() {
        ExecutionLogEntity execution = execution(100L);

        assertThat(deadLetterService.record(execution, "重试预算耗尽")).isTrue();

        ArgumentCaptor<DeadLetterEntity> captor = ArgumentCaptor.forClass(DeadLetterEntity.class);
        verify(deadLetterMapper).insert(captor.capture());
        DeadLetterEntity entity = captor.getValue();
        assertThat(entity.getExecutionId()).isEqualTo(100L);
        assertThat(entity.getTaskId()).isEqualTo(1L);
        assertThat(entity.getTaskName()).isEqualTo("demo-task");
        assertThat(entity.getShardIndex()).isZero();
        assertThat(entity.getShardTotal()).isEqualTo(2);
        assertThat(entity.getRetryCount()).isEqualTo(3);
        assertThat(entity.getLastError()).isEqualTo("重试预算耗尽");
        assertThat(entity.getDeadTime()).isNotNull();

        ArgumentCaptor<AlertMessage> alertCaptor = ArgumentCaptor.forClass(AlertMessage.class);
        verify(alertChannel).publish(alertCaptor.capture());
        assertThat(alertCaptor.getValue().alertType()).isEqualTo(DeadLetterService.ALERT_TYPE_DEAD_LETTER);
        assertThat(alertCaptor.getValue().executionId()).isEqualTo(100L);
        assertThat(alertCaptor.getValue().taskId()).isEqualTo(1L);

        // 4.10.3：真落了一条死信计入 recorded（ADR-022）。
        assertThat(deadLetterCounter("recorded").count()).isEqualTo(1.0);
    }

    @Test
    void duplicateExecutionIdIsIgnored() {
        // 按 execution_id 幂等：同一条执行记录只落一行死信，也不重复告警。
        when(deadLetterMapper.insert(any(DeadLetterEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_dead_execution"));

        assertThat(deadLetterService.record(execution(100L), "重试预算耗尽")).isFalse();

        verify(alertChannel, never()).publish(any(AlertMessage.class));
        // 幂等跳过单独计数为 duplicate。
        assertThat(deadLetterCounter("duplicate").count()).isEqualTo(1.0);
    }

    @Test
    void missingExecutionRecordIsSkipped() {
        assertThat(deadLetterService.record(null, "重试预算耗尽")).isFalse();

        verify(deadLetterMapper, never()).insert(any(DeadLetterEntity.class));
        verify(alertChannel, never()).publish(any(AlertMessage.class));
    }

    private Counter deadLetterCounter(String outcome) {
        return meterRegistry.get(MetricNames.DEADLETTERS).tag("outcome", outcome).counter();
    }

    private ExecutionLogEntity execution(Long id) {
        ExecutionLogEntity execution = new ExecutionLogEntity();
        execution.setId(id);
        execution.setTaskId(1L);
        execution.setTaskName("demo-task");
        execution.setTriggerType(TriggerType.CRON);
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setShardIndex(0);
        execution.setShardTotal(2);
        execution.setRetryCount(3);
        execution.setStartTime(LocalDateTime.now().minusSeconds(30));
        return execution;
    }
}
