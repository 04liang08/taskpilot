package com.taskpilot.scheduler.support;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionKeysTest {

    private static final LocalDateTime SCHEDULED = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    @Test
    void singleTargetShardKeyHasNoExecutorDimension() {
        assertThat(ExecutionKeys.deterministic(3L, SCHEDULED, 0))
                .isEqualTo("3:20260101100000:0");
    }

    @Test
    void multiTargetShardKeyAppendsExecutorId() {
        assertThat(ExecutionKeys.deterministic(3L, SCHEDULED, 1, 11L))
                .isEqualTo("3:20260101100000:1:11");
    }

    @Test
    void differentExecutorsProduceDifferentKeys() {
        // 广播策略下同一个分片会投给多台执行器，键必须能区分开，
        // 否则第二台起就撞唯一索引，广播实际只剩一台在跑。
        String first = ExecutionKeys.deterministic(3L, SCHEDULED, 0, 10L);
        String second = ExecutionKeys.deterministic(3L, SCHEDULED, 0, 11L);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void differentShardIndexesProduceDifferentKeys() {
        assertThat(ExecutionKeys.deterministic(3L, SCHEDULED, 0))
                .isNotEqualTo(ExecutionKeys.deterministic(3L, SCHEDULED, 1));
    }
}
