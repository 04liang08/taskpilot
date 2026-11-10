package com.taskpilot.executor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行器配置，对应 application.yml 中的 taskpilot.executor。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "taskpilot.executor")
public class ExecutorProperties {

    private String appName = "taskpilot-executor";

    private String group = "DEFAULT";

    private String address = "127.0.0.1:8081";

    private String schedulerBaseUrl = "http://127.0.0.1:8080";

    private int heartbeatIntervalSeconds = 10;

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 5000;

    private ThreadPool threadPool = new ThreadPool();

    @Getter
    @Setter
    public static class ThreadPool {

        private int coreSize = 8;

        private int maxSize = 32;

        private int queueCapacity = 256;
    }
}
