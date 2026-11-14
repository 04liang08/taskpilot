package com.taskpilot.scheduler.shard;

import com.taskpilot.scheduler.entity.ExecutorEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 哈希分配：按 taskId + shardIndex 稳定映射到执行器。
 * <p>用 Objects.hash 而不是 String.hashCode 之外的自定义算法，保证同一分片
 * 在不同进程、不同 JVM 上落到同一个执行器（重启后分配结果不变）。</p>
 */
public class HashShardAllocator implements ShardAllocator {

    @Override
    public Map<Integer, List<ExecutorEntity>> allocate(long taskId, int shardTotal, List<ExecutorEntity> executors) {
        Map<Integer, List<ExecutorEntity>> plan = new LinkedHashMap<>();
        if (executors.isEmpty() || shardTotal <= 0) {
            return plan;
        }
        for (int shardIndex = 0; shardIndex < shardTotal; shardIndex++) {
            int position = Math.floorMod(Objects.hash(taskId, shardIndex), executors.size());
            List<ExecutorEntity> targets = new ArrayList<>();
            targets.add(executors.get(position));
            plan.put(shardIndex, targets);
        }
        return plan;
    }
}
