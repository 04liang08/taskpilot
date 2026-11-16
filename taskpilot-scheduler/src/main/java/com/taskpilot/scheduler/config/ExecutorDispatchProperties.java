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
     * 读超时。
     * <p>第 4 周起执行接口只回 ACK（受理与否），回包很快，5 秒足够；
     * 任务本身跑多久由执行器管，超时也由执行器判定并上报。</p>
     */
    private int readTimeoutMs = 5000;
}
