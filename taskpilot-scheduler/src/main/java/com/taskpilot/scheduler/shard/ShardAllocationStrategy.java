package com.taskpilot.scheduler.shard;

/**
 * 分片分配策略。
 */
public enum ShardAllocationStrategy {

    /**
     * 平均分配：分片按顺序轮流落到各执行器上。
     */
    AVERAGE,

    /**
     * 哈希分配：按 taskId + shardIndex 稳定映射到执行器，同一分片总是落到同一个执行器。
     */
    HASH,

    /**
     * 广播：每个分片都发给所有执行器（用于数据同步类任务）。
     */
    BROADCAST
}
