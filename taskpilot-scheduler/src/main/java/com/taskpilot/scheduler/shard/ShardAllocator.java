package com.taskpilot.scheduler.shard;

import com.taskpilot.scheduler.entity.ExecutorEntity;

import java.util.List;
import java.util.Map;

/**
 * 分片分配器：决定每个分片由哪些执行器执行。
 */
public interface ShardAllocator {

    /**
     * 把 shardTotal 个分片分配给在线执行器。
     *
     * @param taskId     任务 id（哈希策略用）
     * @param shardTotal 分片总数
     * @param executors  在线执行器
     * @return shardIndex -&gt; 执行该分片的执行器列表（广播策略下是多个）
     */
    Map<Integer, List<ExecutorEntity>> allocate(long taskId, int shardTotal, List<ExecutorEntity> executors);
}
