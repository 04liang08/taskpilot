package com.taskpilot.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 拒绝计数装饰器：给 AbortPolicy 等既有拒绝策略包一层，先给
 * {@code taskpilot.pool.rejected{pool=...}} 计数 +1，再原样委托。
 * <p>不改变拒绝语义（AbortPolicy 依旧抛 RejectedExecutionException），
 * 只是让"池满被拒"在指标里可见——200 并发拐点时它是关键信号之一。</p>
 */
public class CountingRejectedExecutionHandler implements RejectedExecutionHandler {

    private final Counter rejected;
    private final RejectedExecutionHandler delegate;

    public CountingRejectedExecutionHandler(MeterRegistry registry, String pool, RejectedExecutionHandler delegate) {
        this.rejected = Counter.builder(MetricNames.POOL_REJECTED)
                .description("线程池拒绝的任务数")
                .tag("pool", pool)
                .register(registry);
        this.delegate = delegate;
    }

    @Override
    public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
        rejected.increment();
        delegate.rejectedExecution(runnable, executor);
    }
}
