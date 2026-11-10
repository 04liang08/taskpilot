package com.taskpilot.scheduler.config;

import com.taskpilot.scheduler.client.ExecutorClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 调度中心访问执行器的 RestClient 配置。
 */
@Configuration
@EnableConfigurationProperties(ExecutorDispatchProperties.class)
public class RestClientConfig {

    @Bean
    public ExecutorClient executorClient(ExecutorDispatchProperties properties) {
        return new ExecutorClient(properties);
    }
}
