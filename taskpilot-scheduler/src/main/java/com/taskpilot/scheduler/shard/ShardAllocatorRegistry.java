package com.taskpilot.scheduler.shard;

import com.taskpilot.scheduler.config.SchedulerProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 分片分配器注册表，按配置选择当前策略。
 */
@Component
public class ShardAllocatorRegistry {

    private final Map<ShardAllocationStrategy, ShardAllocator> allocators =
            new EnumMap<>(ShardAllocationStrategy.class);
    private final ShardAllocationStrategy strategy;

    public ShardAllocatorRegistry(SchedulerProperties properties) {
        allocators.put(ShardAllocationStrategy.AVERAGE, new AverageShardAllocator());
        allocators.put(ShardAllocationStrategy.HASH, new HashShardAllocator());
        allocators.put(ShardAllocationStrategy.BROADCAST, new BroadcastShardAllocator());
        this.strategy = properties.getShardAllocation();
    }

    public ShardAllocator current() {
        return allocators.get(strategy);
    }

    public ShardAllocationStrategy strategy() {
        return strategy;
    }
}
