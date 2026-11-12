package com.taskpilot.scheduler.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

    private TriggerPool triggerPool = new TriggerPool();

    @Getter
    @Setter
    public static class TriggerPool {

        private int coreSize = 4;

        private int maxSize = 16;

        private int queueCapacity = 512;
    }
}
