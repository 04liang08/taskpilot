package com.taskpilot.executor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 任务执行线程池。与 Web 线程隔离，避免慢任务占满 Tomcat 线程。
 */
@Configuration
public class ThreadPoolConfig {

    @Bean(name = "taskExecutorPool")
    public ThreadPoolTaskExecutor taskExecutorPool(ExecutorProperties properties) {
        ExecutorProperties.ThreadPool pool = properties.getThreadPool();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setThreadNamePrefix("taskpilot-exec-");
        // 队列满时由调用线程执行，形成天然背压，不直接丢弃任务。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * 调度线程池：接住执行请求，等处理器跑完，再把结果发回调度中心。
     * <p>第 4 周起执行接口只回 ACK，不等结果，所以不能再让 HTTP 线程干等。
     * 这个池的线程数就是"同时能执行多少个分片"。</p>
     * <p>队列满时直接拒绝：调度中心据此把这条记录标成失败并走重试，
     * 比悄悄堆积、最后超时要好。</p>
     */
    @Bean(name = "executionDispatchPool")
    public ThreadPoolTaskExecutor executionDispatchPool(ExecutorProperties properties) {
        ExecutorProperties.DispatchPool pool = properties.getDispatchPool();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setThreadNamePrefix("taskpilot-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
