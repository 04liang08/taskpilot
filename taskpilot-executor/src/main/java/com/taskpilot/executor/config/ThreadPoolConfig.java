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
}
