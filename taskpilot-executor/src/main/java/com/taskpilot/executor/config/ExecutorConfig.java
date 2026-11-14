package com.taskpilot.executor.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 执行器配置注册。
 */
@Configuration
@EnableConfigurationProperties(ExecutorProperties.class)
public class ExecutorConfig {
}
