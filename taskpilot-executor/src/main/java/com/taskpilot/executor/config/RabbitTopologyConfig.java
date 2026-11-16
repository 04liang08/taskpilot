package com.taskpilot.executor.config;

import com.taskpilot.common.mq.MqTopologyDeclarables;
import com.taskpilot.common.util.JsonUtils;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.MessageConverter;
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

    /**
     * 消息按 JSON 收发，不走默认的 Java 序列化（跨进程不可读、版本一改就炸）。
     */
    @Bean
    public MessageConverter taskpilotMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(JsonUtils.mapper());
        // 以消费方法签名推断的类型为准，不依赖消息头里的 __TypeId__。
        converter.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }
}
