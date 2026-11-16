package com.taskpilot.scheduler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ExecutionLogMapper extends BaseMapper<ExecutionLogEntity> {

    /**
     * 回收"结果没回来"的 RUNNING 记录。
     * <p>第 4 周起执行结果是异步回传的：调度中心只拿到 ACK，真正的结果经 RabbitMQ 回来。
     * 如果结果消息丢了（broker 挂了、执行器崩了），记录就会一直停在 RUNNING，
     * 概览的"运行中"一直不降。</p>
     * <p>这里按"任务超时时间 + 宽限"判僵尸，标成 <b>TIMEOUT</b>（不是 FAILED：
     * 任务多半是跑太久或结果没回来，不是"明确失败"），并给定时触发的记录挂上
     * next_retry_time，让退避扫描 / 失败转移接手。这是结果丢失的最后一道防线。</p>
     */
    @Update("""
            UPDATE tp_execution_log e
               JOIN tp_task t ON e.task_id = t.id
               SET e.status = 'TIMEOUT',
                   e.end_time = NOW(3),
                   e.next_retry_time = CASE WHEN e.trigger_type = 'CRON' THEN NOW(3) ELSE NULL END,
                   e.error_message = '执行结果未在超时时间内回传（结果消息可能丢失，调度中心也可能已重启）'
             WHERE e.status = 'RUNNING'
               AND t.deleted = 0
               AND TIMESTAMPDIFF(SECOND, e.start_time, NOW(3)) > t.timeout_seconds + #{graceSeconds}
            """)
    int recoverStaleRunning(@Param("graceSeconds") int graceSeconds);

    /**
     * 原子抢占一次重试：只有把记录从 FAILED/TIMEOUT 改成 RUNNING 成功的那一方才有资格执行。
     * <p>用一条 UPDATE 做乐观抢占：多实例同时看到同一条失败记录时，也只会有一方真正重试，
     * 避免同一个分片被执行两遍（副作用重复）。</p>
     *
     * @return 影响行数：1 表示抢占成功，0 表示已被别人抢走或状态不对
     */
    @Update("""
            UPDATE tp_execution_log
               SET status = 'RUNNING',
                   retry_count = retry_count + 1,
                   start_time = NOW(3),
                   end_time = NULL,
                   error_message = NULL,
                   next_retry_time = NULL
             WHERE id = #{id}
               AND status IN ('FAILED', 'TIMEOUT')
            """)
    int claimForRetry(@Param("id") Long id);

    /**
     * 失败转移专用：抢占重试的同时把记录改指向目标执行器。
     * <p>转移成功后 {@code dispatch()} 复用这条记录落终态；如果 executor 列还指着
     * 已经下线/故障的旧执行器，执行历史就会失真（页面看到的是"在 A 上跑成功"，
     * 实际跑在 B 上）。和 claim 放在同一条 UPDATE 里，避免中间态被别的事件读到。</p>
     * <p>SET / WHERE 与 {@link #claimForRetry(Long)} 保持一致，改一处要同步另一处。</p>
     */
    @Update("""
            UPDATE tp_execution_log
               SET status = 'RUNNING',
                   retry_count = retry_count + 1,
                   start_time = NOW(3),
                   end_time = NULL,
                   error_message = NULL,
                   next_retry_time = NULL,
                   executor_id = #{executorId},
                   executor_address = #{executorAddress}
             WHERE id = #{id}
               AND status IN ('FAILED', 'TIMEOUT')
            """)
    int claimForRetryOn(@Param("id") Long id,
                        @Param("executorId") Long executorId,
                        @Param("executorAddress") String executorAddress);
}
