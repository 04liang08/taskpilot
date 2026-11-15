package com.taskpilot.scheduler.job;

import com.taskpilot.scheduler.mapper.ExecutionLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 僵尸执行记录回收。
 * <p>调度中心在任务执行途中崩溃时，执行记录会永远停在 RUNNING。
 * 这里按"任务超时时间 + 宽限"把它们收尾成 FAILED，保证概览的运行中计数不会失真。</p>
 */
@Component
public class ExecutionRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryJob.class);

    private final ExecutionLogMapper executionLogMapper;
    private final int graceSeconds;

    public ExecutionRecoveryJob(ExecutionLogMapper executionLogMapper,
                                @Value("${taskpilot.execution.stale-grace-seconds:60}") int graceSeconds) {
        this.executionLogMapper = executionLogMapper;
        this.graceSeconds = graceSeconds;
    }

    @Scheduled(fixedDelayString = "#{${taskpilot.execution.recovery-interval-seconds:60} * 1000}")
    public void recoverStaleRunning() {
        try {
            int count = executionLogMapper.recoverStaleRunning(graceSeconds);
            if (count > 0) {
                log.warn("recovered {} stale RUNNING execution record(s)", count);
            }
        } catch (Exception e) {
            log.error("recover stale RUNNING records failed", e);
        }
    }
}
