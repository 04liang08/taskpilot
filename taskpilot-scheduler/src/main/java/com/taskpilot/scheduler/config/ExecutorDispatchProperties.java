package com.taskpilot.scheduler.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调度中心调用执行器的超时配置，对应 taskpilot.executor.dispatch。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "taskpilot.executor.dispatch")
public class ExecutorDispatchProperties {

    private int connectTimeoutMs = 3000;

    /**
     * 未指定任务超时时间时使用的读超时。
     */
    private int readTimeoutMs = 65000;
}
