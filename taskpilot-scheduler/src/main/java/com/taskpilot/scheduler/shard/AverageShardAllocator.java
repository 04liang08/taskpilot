package com.taskpilot.scheduler.shard;

import com.taskpilot.scheduler.entity.ExecutorEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平均分配：分片按顺序轮流落到各执行器。
 * <p>分片数与执行器数不一致时都能覆盖：分片少于执行器时有一部分执行器闲着，
 * 分片多于执行器时部分执行器会拿到多个分片。</p>
 */
public class AverageShardAllocator implements ShardAllocator {

    @Override
    public Map<Integer, List<ExecutorEntity>> allocate(long taskId, int shardTotal, List<ExecutorEntity> executors) {
        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        if (executors.isEmpty() || shardTotal <= 0) {
            return plan;
        }
        for (int shardIndex = 0; shardIndex < shardTotal; shardIndex++) {
            ExecutorEntity executor = executors.get(shardIndex % executors.size());
            List<ExecutorEntity> targets = new ArrayList<>();
            targets.add(executor);
            plan.put(shardIndex, targets);
        }
        return plan;
    }
}
