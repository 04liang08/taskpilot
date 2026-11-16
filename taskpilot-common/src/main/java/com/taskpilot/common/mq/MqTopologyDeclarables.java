package com.taskpilot.common.mq;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * RabbitMQ 拓扑声明。scheduler 与 executor 都声明同一份定义：
 * <ul>
 *   <li>执行器是生产者，必须保证队列存在，否则结果消息会被 broker 直接丢掉；</li>
 *   <li>调度中心是消费者，也要保证自己订阅的队列存在。</li>
 * </ul>
 * <p>声明是幂等的（RabbitAdmin 只在缺失时创建），两边都声明不会冲突。
 * 交换机与队列全部持久化：broker 重启后拓扑与未消费的消息都还在。</p>
 */
public final class MqTopologyDeclarables {

    private MqTopologyDeclarables() {
    }

    public static Declarables create() {
        DirectExchange results = new DirectExchange(MqTopology.RESULTS_EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(MqTopology.DLX_EXCHANGE, true, false);
        FanoutExchange alerts = new FanoutExchange(MqTopology.ALERT_EXCHANGE, true, false);

        // 结果队列挂死信交换机：消费端处理失败（nack requeue=false）时消息自动进死信队列。
        Queue resultsQueue = QueueBuilder.durable(MqTopology.RESULTS_QUEUE)
                .withArgument("x-dead-letter-exchange", MqTopology.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqTopology.DEAD_ROUTING_KEY)
                .build();
        Queue deadQueue = QueueBuilder.durable(MqTopology.DEAD_QUEUE).build();
        Queue alertQueue = QueueBuilder.durable(MqTopology.ALERT_QUEUE).build();

        return new Declarables(
                results,
                dlx,
                alerts,
                resultsQueue,
                deadQueue,
                alertQueue,
                BindingBuilder.bind(resultsQueue).to(results).with(MqTopology.RESULTS_ROUTING_KEY),
                BindingBuilder.bind(deadQueue).to(dlx).with(MqTopology.DEAD_ROUTING_KEY),
                // fanout 交换机忽略 routing key，绑上去就能收到全部告警。
                BindingBuilder.bind(alertQueue).to(alerts));
    }
}
