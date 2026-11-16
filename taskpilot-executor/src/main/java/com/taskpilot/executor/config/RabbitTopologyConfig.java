package com.taskpilot.executor.config;

import com.taskpilot.common.mq.MqTopologyDeclarables;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明。声明放在 common 的 {@link MqTopologyDeclarables}，
 * 调度中心与执行器引用同一份定义，避免两边名字漂移。
 *
 * <p>执行器是生产者，必须自己保证结果队列存在：否则调度中心还没起来时，
 * 消息会被 broker 直接丢掉（没有队列就没有路由），执行也就白跑了。</p>
 */
@Configuration
public class RabbitTopologyConfig {

    @Bean
    public Declarables taskpilotMqTopology() {
        return MqTopologyDeclarables.create();
    }
}
