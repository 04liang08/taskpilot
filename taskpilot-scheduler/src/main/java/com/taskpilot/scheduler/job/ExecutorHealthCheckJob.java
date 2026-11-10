package com.taskpilot.scheduler.job;

import com.taskpilot.scheduler.service.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 第 1 周临时下线检测：心跳超时的执行器标记为 OFFLINE。
 * <p>第 2 周会被正式失败检测替代，第 3 周在下线后做分片重分配。</p>
 */
@Component
public class ExecutorHealthCheckJob {

    private static final Logger log = LoggerFactory.getLogger(ExecutorHealthCheckJob.class);

    private final ExecutorService executorService;
    private final long heartbeatTimeoutSeconds;

    public ExecutorHealthCheckJob(ExecutorService executorService,
                                  @Value("${taskpilot.executor.heartbeat-timeout-seconds:30}")
                                  long heartbeatTimeoutSeconds) {
        this.executorService = executorService;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "#{${taskpilot.executor.health-check-interval-seconds:10} * 1000}")
    public void markStaleExecutorsOffline() {
        int count = executorService.markStaleOffline(heartbeatTimeoutSeconds);
        if (count > 0) {
            log.info("marked {} executor(s) OFFLINE, heartbeat timeout={}s", count, heartbeatTimeoutSeconds);
        }
    }
}
