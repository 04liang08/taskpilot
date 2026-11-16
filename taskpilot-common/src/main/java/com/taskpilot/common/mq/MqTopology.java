package com.taskpilot.common.mq;

/**
 * RabbitMQ 拓扑常量。scheduler 与 executor 共用同一套名字，
 * 声明逻辑放在 {@link MqTopologyDeclarables}，两边引用同一份定义，避免名字漂移。
 *
 * <p>拓扑（全部持久化）：</p>
 * <pre>
 * taskpilot.execution.results (direct) --execution-result--> taskpilot.execution.results.q
 * taskpilot.execution.results.q --(消费失败)--> taskpilot.execution.dlx (direct) --> taskpilot.execution.dead
 * taskpilot.alerts (fanout) --> taskpilot.alerts.webhook
 * </pre>
 */
public final class MqTopology {

    /** 执行结果交换机：执行器 -> 调度中心。 */
    public static final String RESULTS_EXCHANGE = "taskpilot.execution.results";

    /** 执行结果队列：调度中心消费。 */
    public static final String RESULTS_QUEUE = "taskpilot.execution.results.q";

    /** 执行结果的 routing key。 */
    public static final String RESULTS_ROUTING_KEY = "execution-result";

    /** 死信交换机：结果队列挂了消息走这里。 */
    public static final String DLX_EXCHANGE = "taskpilot.execution.dlx";

    /** 死信队列：毒消息与处理失败的消息落这里。 */
    public static final String DEAD_QUEUE = "taskpilot.execution.dead";

    /** 死信的 routing key。 */
    public static final String DEAD_ROUTING_KEY = "execution-dead";

    /** 告警交换机（fanout）：一份告警广播给所有订阅方。 */
    public static final String ALERT_EXCHANGE = "taskpilot.alerts";

    /** 告警队列：webhook 消费者绑定在这里。 */
    public static final String ALERT_QUEUE = "taskpilot.alerts.webhook";

    private MqTopology() {
    }
}
