package com.taskpilot.scheduler.config;

import com.taskpilot.common.mq.MqTopologyDeclarables;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明。声明放在 common 的 {@link MqTopologyDeclarables}，
 * 调度中心与执行器引用同一份定义，避免两边名字漂移。
 *
 * <p>broker 不可达时应用仍能启动：RabbitTemplate 与监听容器都是懒连接，
 * 连接失败只会打日志重试，不会阻塞启动（和 Redisson 的处理方式一致）。</p>
 */
@Configuration
public class RabbitTopologyConfig {

    @Bean
    public Declarables taskpilotMqTopology() {
        return MqTopologyDeclarables.create();
    }
}
