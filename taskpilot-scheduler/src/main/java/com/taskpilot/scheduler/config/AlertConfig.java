package com.taskpilot.scheduler.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 告警配置注册。
 */
@Configuration
@EnableConfigurationProperties(AlertProperties.class)
public class AlertConfig {
}
