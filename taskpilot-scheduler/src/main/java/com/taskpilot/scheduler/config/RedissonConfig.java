package com.taskpilot.scheduler.config;

import org.redisson.config.Config;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端。显式建 Bean，避免依赖 starter 的属性名约定（spring.redis.* / spring.data.redis.*）。
 * <p>开启懒初始化：Redis 暂时不可用时应用照样能启动，只是拿不到 leader 身份、不做定时触发。</p>
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(SchedulerProperties properties) {
        Config config = new Config();
        config.setLazyInitialization(true);
        config.useSingleServer()
                .setAddress(properties.getElection().getRedisAddress())
                .setTimeout(3000)
                .setRetryAttempts(2);
        return Redisson.create(config);
    }
}
