package com.taskpilot.scheduler.schedule;

/**
 * 错过触发（misfire）的补偿策略。
 */
public enum MisfirePolicy {

    /**
     * 跳过：进程停机期间错过的触发不补跑，直接推进到下一个未来时间。
     */
    SKIP,

    /**
     * 补跑一次：落后在阈值内的错过触发补执行一次，然后推进到下一个未来时间。
     */
    FIRE_ONCE
}
