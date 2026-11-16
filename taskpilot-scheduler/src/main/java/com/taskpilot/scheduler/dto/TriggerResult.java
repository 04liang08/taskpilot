package com.taskpilot.scheduler.dto;

import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;

import java.util.List;

/**
 * 触发结果。分片任务一次会产生多条执行记录，这里统一返回。
 *
 * <p>第 4 周起触发是异步的：接口返回时记录还停在 RUNNING，结果由 MQ 回传后才落终态。
 * 所以汇总状态可能是 RUNNING，调用方要靠它判断"已提交"还是"已完成"。</p>
 *
 * @param executionId 代表性执行记录 ID（分片时取第一条）
 * @param status      汇总状态：任一分片失败/超时即视为失败；有分片还在跑则为 RUNNING
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
            ExecutionStatus current = execution.getStatus();
            if (current == ExecutionStatus.FAILED || current == ExecutionStatus.TIMEOUT) {
                // 失败优先级最高：只要有一个分片失败，整体就是失败。
                status = current;
            } else if (current == ExecutionStatus.RUNNING && status == ExecutionStatus.SUCCESS) {
                // 还没跑完（结果经 MQ 回传）：不能当成成功。
                status = ExecutionStatus.RUNNING;
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
