package com.taskpilot.scheduler.schedule;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时间轮：按秒对齐的槽位集合。
 * <p>任务按计划触发时间落到对应槽位（slot = epochSecond % wheelSize），
 * 每秒推进一次，只扫描当前这一格；相比每秒扫表，DB 访问量与任务总数无关。</p>
 * <p>同一个任务只会在一个槽位里出现：重新排期时先把旧槽位里的残留清掉，
 * 否则旧时间点仍会触发，而且两个条目各自推进会把触发频率翻倍。</p>
 * <p>线程安全：时间轮线程、Web 线程（事务提交后的事件）、选举线程都会碰它，
 * 所以公开方法全部加锁。操作都是 O(wheelSize) 的小扫描，粗锁足够。</p>
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
     * 放入计划触发时间。同一任务重复放入会以新时间为准，旧的槽位条目会被清掉。
     */
    public synchronized void schedule(long taskId, long triggerEpochSecond) {
        // 先清旧条目：只 put 新槽位的话，旧时间点还会再触发一次，
        // 而且两条记录各自推进，稳态下触发频率直接翻倍。
        removeFromAllBuckets(taskId);
        buckets[slotOf(triggerEpochSecond)].put(taskId, triggerEpochSecond);
    }

    /**
     * 移出任务（任务停用、删除或改 cron 时调用）。
     */
    public synchronized void cancel(long taskId) {
        removeFromAllBuckets(taskId);
    }

    /**
     * 取出到期的任务：计划时间 &lt;= nowEpochSecond 的会被取出并从轮中移除。
     * <p>轮转回来但还没到点的任务会留在槽位里，等它的分钟到来。</p>
     */
    public synchronized Map<Long, Long> pollDue(long nowEpochSecond) {
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

    /**
     * 清空时间轮（失去 leader 身份时调用）。
     */
    public synchronized void clear() {
        for (Map<Long, Long> bucket : buckets) {
            bucket.clear();
        }
    }

    /**
     * 查某个任务当前排在轮子里的触发时间；不在轮子里返回 null。
     */
    public synchronized Long scheduledTimeOf(long taskId) {
        for (Map<Long, Long> bucket : buckets) {
            Long scheduled = bucket.get(taskId);
            if (scheduled != null) {
                return scheduled;
            }
        }
        return null;
    }

    public synchronized int size() {
        int total = 0;
        for (Map<Long, Long> bucket : buckets) {
            total += bucket.size();
        }
        return total;
    }

    public synchronized int getWheelSize() {
        return wheelSize;
    }

    /**
     * 从所有槽位里清掉这个任务。
     * <p>不维护"任务 → 槽位"的反查索引：轮子很小（默认 60 格），
     * 扫一遍比维护索引更不容易出错，也不用操心索引和实际内容对不上。</p>
     */
    private void removeFromAllBuckets(long taskId) {
        for (Map<Long, Long> bucket : buckets) {
            bucket.remove(taskId);
        }
    }

    private int slotOf(long epochSecond) {
        return (int) Math.floorMod(epochSecond, (long) wheelSize);
    }
}
