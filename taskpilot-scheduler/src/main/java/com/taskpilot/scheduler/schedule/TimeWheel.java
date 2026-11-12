package com.taskpilot.scheduler.schedule;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时间轮：按秒对齐的槽位集合。
 * <p>任务按计划触发时间落到对应槽位（slot = epochSecond % wheelSize），
 * 每秒推进一次，只扫描当前这一格；相比每秒扫表，DB 访问量与任务总数无关。</p>
 * <p>同一个任务只会在一个槽位里出现；轮转到下一轮时由 {@link #schedule} 重新放入新槽位。</p>
 */
public class TimeWheel {

    private final int wheelSize;
    private final Map<Long, Long>[] buckets;

    @SuppressWarnings("unchecked")
    public TimeWheel(int wheelSize) {
        if (wheelSize <= 0) {
            throw new IllegalArgumentException("wheelSize 必须大于 0");
        }
        this.wheelSize = wheelSize;
        this.buckets = new Map[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            this.buckets[i] = new LinkedHashMap<>();
        }
    }

    /**
     * 放入计划触发时间。同一任务重复放入会以新时间为准。
     */
    public void schedule(long taskId, long triggerEpochSecond) {
        buckets[slotOf(triggerEpochSecond)].put(taskId, triggerEpochSecond);
    }

    /**
     * 移出任务（任务停用、删除或改 cron 时调用）。
     */
    public void cancel(long taskId) {
        for (Map<Long, Long> bucket : buckets) {
            bucket.remove(taskId);
        }
    }

    /**
     * 取出到期的任务：计划时间 &lt;= nowEpochSecond 的会被取出并从轮中移除。
     * <p>轮转回来但还没到点的任务会留在槽位里，等它的分钟到来。</p>
     */
    public Map<Long, Long> pollDue(long nowEpochSecond) {
        Map<Long, Long> due = new LinkedHashMap<>();
        Map<Long, Long> bucket = buckets[slotOf(nowEpochSecond)];
        Iterator<Map.Entry<Long, Long>> iterator = bucket.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Long> entry = iterator.next();
            if (entry.getValue() <= nowEpochSecond) {
                due.put(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
        return due;
    }

    public int size() {
        int total = 0;
        for (Map<Long, Long> bucket : buckets) {
            total += bucket.size();
        }
        return total;
    }

    public int getWheelSize() {
        return wheelSize;
    }

    private int slotOf(long epochSecond) {
        return (int) Math.floorMod(epochSecond, (long) wheelSize);
    }
}
