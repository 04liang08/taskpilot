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

    /**
     * 调度中心地址，支持逗号分隔多个：
     * {@code scheduler-base-url: http://127.0.0.1:8080,http://127.0.0.1:8090}
     * <p>多个地址是冗余保护：一个调度中心挂掉时，执行器仍能通过另一个地址心跳。</p>
     * <p>这里刻意用 String 而不是 List：Spring Boot 的 @ConfigurationProperties
     * 对 List 的绑定对逗号分隔字符串的处理并不直观，手动 split 更可控。</p>
     */
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
