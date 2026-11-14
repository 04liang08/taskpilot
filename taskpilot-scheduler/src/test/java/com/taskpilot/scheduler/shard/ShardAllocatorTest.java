package com.taskpilot.scheduler.shard;

import com.taskpilot.scheduler.entity.ExecutorEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShardAllocatorTest {

    private final AverageShardAllocator average = new AverageShardAllocator();
    private final HashShardAllocator hash = new HashShardAllocator();
    private final BroadcastShardAllocator broadcast = new BroadcastShardAllocator();

    @Test
    void averageDistributesShardsRoundRobin() {
        Map<Integer, List<ExecutorEntity>> plan =
                average.allocate(1L, 4, List.of(executor(10L), executor(11L)));

        assertThat(plan).hasSize(4);
        assertThat(ids(plan.get(0))).containsExactly(10L);
        assertThat(ids(plan.get(1))).containsExactly(11L);
        assertThat(ids(plan.get(2))).containsExactly(10L);
        assertThat(ids(plan.get(3))).containsExactly(11L);
    }

    @Test
    void averageLeavesIdleExecutorsWhenShardsFewer() {
        Map<Integer, List<ExecutorEntity>> plan =
                average.allocate(1L, 2, List.of(executor(10L), executor(11L), executor(12L)));

        assertThat(plan).hasSize(2);
        assertThat(ids(plan.get(0))).containsExactly(10L);
        assertThat(ids(plan.get(1))).containsExactly(11L);
    }

    @Test
    void hashAssignmentIsStableForSameTaskAndShard() {
        List<ExecutorEntity> executors = List.of(executor(10L), executor(11L), executor(12L));

        Map<Integer, List<ExecutorEntity>> first = hash.allocate(42L, 6, executors);
        Map<Integer, List<ExecutorEntity>> second = hash.allocate(42L, 6, executors);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(6);
        first.forEach((shard, targets) -> assertThat(targets).hasSize(1));
    }

    @Test
    void hashKeepsShardOnSameExecutorAcrossCalls() {
        List<ExecutorEntity> executors = List.of(executor(10L), executor(11L), executor(12L));

        ExecutorEntity first = hash.allocate(42L, 3, executors).get(1).get(0);
        ExecutorEntity second = hash.allocate(42L, 3, executors).get(1).get(0);

        assertThat(first.getId()).isEqualTo(second.getId());
    }

    @Test
    void broadcastSendsEveryShardToAllExecutors() {
        Map<Integer, List<ExecutorEntity>> plan =
                broadcast.allocate(1L, 3, List.of(executor(10L), executor(11L)));

        assertThat(plan).hasSize(3);
        plan.forEach((shard, targets) -> assertThat(ids(targets)).containsExactly(10L, 11L));
    }

    @Test
    void emptyExecutorsProduceEmptyPlan() {
        assertThat(average.allocate(1L, 3, List.of())).isEmpty();
        assertThat(hash.allocate(1L, 3, List.of())).isEmpty();
        assertThat(broadcast.allocate(1L, 3, List.of())).isEmpty();
    }

    @Test
    void zeroShardsProduceEmptyPlan() {
        assertThat(average.allocate(1L, 0, List.of(executor(10L)))).isEmpty();
        assertThat(hash.allocate(1L, 0, List.of(executor(10L)))).isEmpty();
        assertThat(broadcast.allocate(1L, 0, List.of(executor(10L)))).isEmpty();
    }

    private List<Long> ids(List<ExecutorEntity> executors) {
        return executors.stream().map(ExecutorEntity::getId).toList();
    }

    private ExecutorEntity executor(Long id) {
        ExecutorEntity entity = new ExecutorEntity();
        entity.setId(id);
        entity.setAddress("127.0.0.1:" + (8000 + id));
        return entity;
    }
}
