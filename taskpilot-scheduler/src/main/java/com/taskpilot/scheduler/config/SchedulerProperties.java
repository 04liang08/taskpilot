package com.taskpilot.scheduler.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.taskpilot.scheduler.schedule.MisfirePolicy;
import com.taskpilot.scheduler.shard.ShardAllocationStrategy;

/**
 * 调度中心自身的调度参数，对应 taskpilot.scheduler。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "taskpilot.scheduler")
public class SchedulerProperties {

    /**
     * 时间轮槽位数量。槽位按秒对齐，一个槽位代表一轮里的同一秒。
     */
    private int wheelSize = 60;

    /**
     * 时间轮 tick 间隔（毫秒）。
     */
    private long tickMillis = 1000;

    /**
     * 单次 tick 最多回补多少秒。落后太多（进程长时间挂起）时直接跳到当前秒，避免空转。
     */
    private int maxCatchUpSeconds = 300;

    /**
     * 错过触发（进程停机期间到点）的补偿策略。
     */
    private MisfirePolicy misfirePolicy = MisfirePolicy.SKIP;

    /**
     * 补跑阈值（秒）。落后超过这个时间的错过触发不再补跑，避免启动时打出一串历史触发。
     */
    private int misfireThresholdSeconds = 300;

    /**
     * leader 与 DB 对齐任务列表的间隔（毫秒）。
     * <p>任务事件只在进程内传播，别的实例创建的任务靠这个周期同步过来。</p>
     */
    private long taskSyncIntervalMs = 5000;

    /**
     * 分片分配策略：平均 / 哈希 / 广播。
     */
    private ShardAllocationStrategy shardAllocation = ShardAllocationStrategy.AVERAGE;

    private TriggerPool triggerPool = new TriggerPool();

    private Election election = new Election();

    @Getter
    @Setter
    public static class TriggerPool {

        private int coreSize = 4;

        private int maxSize = 16;

        private int queueCapacity = 512;
    }

    /**
     * 领导选举配置。单机开发可以关掉 election.enabled，此时本实例恒为 leader 且不做 fencing 校验。
     */
    @Getter
    @Setter
    public static class Election {

        /**
         * 是否启用领导选举。关掉后本实例直接充当 leader。
         */
        private boolean enabled = true;

        private String redisAddress = "redis://127.0.0.1:6379";

        private String lockKey = "taskpilot:scheduler:leader";

        private String tokenKey = "taskpilot:scheduler:fence:token";

        /**
         * 竞选间隔（毫秒）。
         */
        private long retryIntervalMs = 2000;

        /**
         * 锁租约（秒）。Redisson watchdog 会自动续期，这里只是兜底上限。
         */
        private long leaseSeconds = 30;
    }
}
