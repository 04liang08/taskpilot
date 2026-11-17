package com.taskpilot.scheduler.config;

import com.taskpilot.common.metrics.CountingRejectedExecutionHandler;
import com.taskpilot.common.metrics.MetricNames;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 线程池指标（4.10.2）：
 * <ul>
 *   <li>拒绝计数装饰器：计数 +1 且不改 Abort 语义；</li>
 *   <li>池绑定 gauge 的契约：与 actuator 对 ThreadPoolTaskExecutor bean 的自动绑定同款调用，
 *       断言 executor_queued 等时间线存在；</li>
 *   <li>TriggerPoolConfig 确实把两个池的拒绝处理器换成了装饰器。</li>
 * </ul>
 */
class PoolMetricsTest {

    @Test
    void rejectionIsCountedAndStillAborts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        CountingRejectedExecutionHandler handler =
                new CountingRejectedExecutionHandler(registry, "triggerExecutor", new ThreadPoolExecutor.AbortPolicy());

        // 装饰器只做计数与委托：直接调用也应计数并原样抛出 RejectedExecutionException。
        assertThatThrownBy(() -> handler.rejectedExecution(() -> { }, pool))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThat(registry.get(MetricNames.POOL_REJECTED).tag("pool", "triggerExecutor").counter().count())
                .isEqualTo(1.0);
        pool.shutdownNow();
    }

    @Test
    void executorGaugesExistAfterBinding() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        ExecutorServiceMetrics.monitor(registry, executor.getThreadPoolExecutor(), "shardExecutor",
                Tags.of("pool", "shardExecutor"));

        // actuator 对 @Bean ThreadPoolTaskExecutor 自动绑定的正是这套时间线，这里断言契约存在。
        // 注意 Micrometer 侧是点号名（executor.queued），executor_queued_tasks 是 Prometheus 渲染名。
        assertThat(registry.get("executor.queued").tag("name", "shardExecutor").gauge().value()).isZero();
        assertThat(registry.get("executor.pool.size").tag("name", "shardExecutor").gauge()).isNotNull();
        executor.shutdown();
    }

    @Test
    void triggerPoolUsesCountingHandler() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TriggerPoolConfig config = new TriggerPoolConfig();
        ThreadPoolTaskExecutor executor = config.triggerExecutor(new SchedulerProperties(), registry);

        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(CountingRejectedExecutionHandler.class);
        executor.shutdown();
    }

    @Test
    void shardPoolUsesCountingHandler() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TriggerPoolConfig config = new TriggerPoolConfig();
        ThreadPoolTaskExecutor executor = config.shardExecutor(new SchedulerProperties(), registry);

        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(CountingRejectedExecutionHandler.class);
        executor.shutdown();
    }
}
