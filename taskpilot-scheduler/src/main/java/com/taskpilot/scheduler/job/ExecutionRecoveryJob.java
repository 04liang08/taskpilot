package com.taskpilot.scheduler.job;

import com.taskpilot.scheduler.mapper.ExecutionLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 僵尸执行记录回收。
 * <p>第 4 周起执行结果异步回传，这个任务就是"结果消息丢了"的最后防线：
 * 已经 ACK（执行器受理了）却迟迟没有结果的记录，超过"任务超时时间 + 宽限"后
 * 标成 TIMEOUT，并给定时触发的记录挂上 next_retry_time，
 * 交给退避扫描 / 失败转移接手重试。</p>
 * <p>注意：正常的任务超时由执行器判定并上报，这里只兜底"结果没回来"的情况。</p>
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
                log.warn("recovered {} stale RUNNING execution record(s) as TIMEOUT", count);
            }
        } catch (Exception e) {
            log.error("recover stale RUNNING records failed", e);
        }
    }
}
