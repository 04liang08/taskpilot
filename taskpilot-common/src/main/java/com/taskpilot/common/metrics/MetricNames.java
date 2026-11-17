package com.taskpilot.common.metrics;

/**
 * 自定义指标名常量：Micrometer 点号风格，Prometheus 侧渲染成下划线（counter 带 _total）。
 * <p>铁律：标签只用低基数枚举（status / trigger_type / outcome / action / pool / handler），
 * 绝不带 taskId / executionId / executorAddress——那会让 Prometheus 时间线基数爆炸。
 * 见 ADR-022。</p>
 */
public final class MetricNames {

    /** 触发计数。tag: trigger_type=cron|manual, outcome=dispatched|conflict_running|fencing_rejected|no_executor */
    public static final String TRIGGERS = "taskpilot.triggers";

    /** 派发 ACK 往返耗时（Timer）。tag: outcome=accepted|rejected|error */
    public static final String DISPATCH = "taskpilot.dispatch";

    /** 结果消息处理计数。tag: status=success|failed|timeout, outcome=applied|ignored|dispatch_failure */
    public static final String EXECUTION_RESULTS = "taskpilot.execution.results";

    /**
     * 执行耗时。调度中心侧是 DistributionSummary（毫秒，取执行器上报的 durationMs），
     * 执行器侧是 Timer（业务处理器真实耗时）；Prometheus 渲染名分别为
     * taskpilot_execution_duration_milliseconds_* 与 taskpilot_execution_duration_seconds_*，不会互相覆盖。
     * tag: status / handler
     */
    public static final String EXECUTION_DURATION = "taskpilot.execution.duration";

    /** 重试计数。tag: action=scheduled|exhausted|handed_over */
    public static final String RETRIES = "taskpilot.retries";

    /** 失败转移计数。tag: outcome=takeover|skipped_online|skipped_manual|exhausted */
    public static final String FAILOVERS = "taskpilot.failovers";

    /** 死信计数。tag: outcome=recorded|duplicate */
    public static final String DEADLETTERS = "taskpilot.deadletters";

    /** leader 身份 Gauge（1=leader，0=follower） */
    public static final String LEADER = "taskpilot.leader";

    /** 角色切换计数。tag: to=leader|follower */
    public static final String LEADER_TRANSITIONS = "taskpilot.leader.transitions";

    /** 线程池拒绝计数。tag: pool=triggerExecutor|shardExecutor|executionDispatchPool */
    public static final String POOL_REJECTED = "taskpilot.pool.rejected";

    /** 执行器完成执行计数。tag: status=success|failed|timeout, handler=<handlerName> */
    public static final String EXECUTIONS = "taskpilot.executions";

    /** 结果消息发布计数（publisher confirm）。tag: outcome=confirmed|gave_up */
    public static final String RESULTS_PUBLISHED = "taskpilot.results.published";

    private MetricNames() {
    }
}
