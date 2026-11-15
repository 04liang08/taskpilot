package com.taskpilot.scheduler.shard;

/**
 * 分片执行失败事件。调度中心在调用执行器失败后发布，由失败转移逻辑决定是否重投。
 *
 * @param executionId 失败的执行记录 id
 * @param taskId      任务 id
 * @param executorId  失败时所在的执行器 id
 */
public record ShardFailedEvent(Long executionId, Long taskId, Long executorId) {
}
