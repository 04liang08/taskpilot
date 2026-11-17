package com.taskpilot.scheduler.leader;

import com.taskpilot.common.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * leader 可观测性（ADR-022）：本机角色 0/1 gauge + 角色切换计数。
 * <p>独立组件而不是改 {@link LeaderElectionService}：零侵入，
 * 选举逻辑与既有选举单测都不动，指标只订阅已发布的 {@link LeaderChangedEvent}。</p>
 */
@Component
public class LeaderMetrics {

    private final MeterRegistry meterRegistry;

    public LeaderMetrics(MeterRegistry meterRegistry, LeaderElectionService election) {
        this.meterRegistry = meterRegistry;
        // 状态对象是单例 bean，micrometer 的弱引用不会把它回收掉。
        Gauge.builder(MetricNames.LEADER, election, e -> e.isLeader() ? 1.0 : 0.0)
                .description("本实例是否为 leader（1=是，0=否）")
                .register(meterRegistry);
    }

    @EventListener
    public void onLeaderChanged(LeaderChangedEvent event) {
        Counter.builder(MetricNames.LEADER_TRANSITIONS)
                .description("leader 角色切换次数")
                .tag("to", event.leader() ? "leader" : "follower")
                .register(meterRegistry)
                .increment();
    }
}
