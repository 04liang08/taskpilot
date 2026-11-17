package com.taskpilot.scheduler.leader;

import com.taskpilot.common.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * leader 可观测性（4.10.3）：gauge 跟随选举状态，切换计数按角色区分（ADR-022）。
 */
class LeaderMetricsTest {

    @Test
    void gaugeFollowsElectionAndTransitionsAreCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LeaderElectionService election = mock(LeaderElectionService.class);
        when(election.isLeader()).thenReturn(true);

        LeaderMetrics metrics = new LeaderMetrics(registry, election);

        assertThat(registry.get(MetricNames.LEADER).gauge().value()).isEqualTo(1.0);

        when(election.isLeader()).thenReturn(false);
        assertThat(registry.get(MetricNames.LEADER).gauge().value()).isZero();

        metrics.onLeaderChanged(new LeaderChangedEvent(true, 7L));
        metrics.onLeaderChanged(new LeaderChangedEvent(true, 8L));
        metrics.onLeaderChanged(new LeaderChangedEvent(false, null));

        assertThat(registry.get(MetricNames.LEADER_TRANSITIONS).tag("to", "leader").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(MetricNames.LEADER_TRANSITIONS).tag("to", "follower").counter().count())
                .isEqualTo(1.0);
    }
}
