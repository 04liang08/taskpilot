package com.taskpilot.scheduler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ExecutionLogMapper extends BaseMapper<ExecutionLogEntity> {

    /**
     * 回收僵尸 RUNNING 记录。
     * <p>调度中心在执行途中崩溃/被杀时，记录会永远停在 RUNNING（概览的"运行中"一直不降）。
     * 判据用任务自己的超时时间做基准：超过 task.timeout_seconds + graceSeconds 才算僵尸，
     * 避免把正在跑的长任务误判掉。</p>
     */
    @Update("""
            UPDATE tp_execution_log e
               JOIN tp_task t ON e.task_id = t.id
               SET e.status = 'FAILED',
                   e.end_time = NOW(3),
                   e.error_message = '执行记录超时未回收（调度中心可能已重启）'
             WHERE e.status = 'RUNNING'
               AND t.deleted = 0
               AND TIMESTAMPDIFF(SECOND, e.start_time, NOW(3)) > t.timeout_seconds + #{graceSeconds}
            """)
    int recoverStaleRunning(@Param("graceSeconds") int graceSeconds);
}
