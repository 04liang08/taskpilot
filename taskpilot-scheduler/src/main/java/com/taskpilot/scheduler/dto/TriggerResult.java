package com.taskpilot.scheduler.dto;

import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;

import java.util.List;

/**
 * 触发结果。分片任务一次会产生多条执行记录，这里统一返回。
 *
 * @param executionId 代表性执行记录 ID（分片时取第一条）
 * @param status      汇总状态：任一分片失败/超时即视为失败
 * @param shardTotal  分片总数
 * @param durationMs  代表性执行记录的耗时
 * @param executions  全部执行记录
 */
public record TriggerResult(
        Long executionId,
        ExecutionStatus status,
        int shardTotal,
        Long durationMs,
        List<ExecutionLogEntity> executions) {

    public static TriggerResult of(List<ExecutionLogEntity> executions) {
        List<ExecutionLogEntity> list = List.copyOf(executions);
        ExecutionStatus status = ExecutionStatus.SUCCESS;
        int shardTotal = 1;
        for (ExecutionLogEntity execution : list) {
            if (execution.getShardTotal() != null) {
                shardTotal = execution.getShardTotal();
            }
            if (execution.getStatus() == ExecutionStatus.FAILED
                    || execution.getStatus() == ExecutionStatus.TIMEOUT) {
                status = execution.getStatus();
            }
        }
        ExecutionLogEntity first = list.isEmpty() ? null : list.get(0);
        return new TriggerResult(first == null ? null : first.getId(),
                status,
                shardTotal,
                first == null ? null : first.getDurationMs(),
                list);
    }
}
