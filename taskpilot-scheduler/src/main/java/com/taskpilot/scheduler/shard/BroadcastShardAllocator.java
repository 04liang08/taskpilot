package com.taskpilot.scheduler.shard;

import com.taskpilot.scheduler.entity.ExecutorEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 广播分配：每个分片都发给所有在线执行器。
 * <p>用于"每个节点都要跑一遍"的数据同步类任务，注意执行次数 = 分片数 × 执行器数。</p>
 */
public class BroadcastShardAllocator implements ShardAllocator {

    @Override
    public Map<Integer, List<ExecutorEntity>> allocate(long taskId, int shardTotal, List<ExecutorEntity> executors) {
        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        if (executors.isEmpty() || shardTotal <= 0) {
            return plan;
        }
        for (int shardIndex = 0; shardIndex < shardTotal; shardIndex++) {
            plan.put(shardIndex, new ArrayList<>(executors));
        }
        return plan;
    }
}
