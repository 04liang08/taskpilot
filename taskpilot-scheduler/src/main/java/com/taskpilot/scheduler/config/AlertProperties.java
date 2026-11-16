package com.taskpilot.scheduler.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 告警配置，对应 taskpilot.alert。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "taskpilot.alert")
public class AlertProperties {

    /**
     * 告警 webhook 地址。
     * <p>可选项：不配就只把告警记在日志里，不做任何外发。
     * 配了就由告警消费者 POST 过去（消息体是 AlertMessage 的 JSON）。</p>
     */
    private String webhookUrl;

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 5000;
}
