package com.taskpilot.scheduler.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 定时触发使用的线程池。
 * <p>时间轮线程只负责"到点了就发起"，绝不能被执行中的长任务阻塞，
 * 所以触发动作单独放一个池，和 Web 线程、时间轮线程都隔开。</p>
 */
@Configuration
@EnableConfigurationProperties(SchedulerProperties.class)
public class TriggerPoolConfig {

    @Bean(name = "triggerExecutor")
    public ThreadPoolTaskExecutor triggerExecutor(SchedulerProperties properties) {
        SchedulerProperties.TriggerPool pool = properties.getTriggerPool();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setThreadNamePrefix("taskpilot-trigger-");
        // 队列满时直接拒绝并告警：让触发线程阻塞会拖慢整个时间轮，宁可丢一次触发。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
