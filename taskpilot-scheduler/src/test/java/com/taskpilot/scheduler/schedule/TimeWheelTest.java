package com.taskpilot.scheduler.schedule;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TimeWheelTest {

    @Test
    void pollDueOnlyReturnsReachedTasks() {
        TimeWheel wheel = new TimeWheel(60);
        wheel.schedule(1L, 1000L);
        // 同一秒的下一轮，还没到点，应该留在轮里。
        wheel.schedule(2L, 1060L);

        Map<Long, Long> due = wheel.pollDue(1000L);

        assertThat(due).containsOnlyKeys(1L);
        assertThat(due.get(1L)).isEqualTo(1000L);
        assertThat(wheel.size()).isEqualTo(1);
    }

    @Test
    void pollDueTakesOverdueTaskFromPreviousRound() {
        TimeWheel wheel = new TimeWheel(60);
        // 计划时间已经过去（进程停过一轮），轮转回来时要能补取出来。
        wheel.schedule(1L, 940L);

        Map<Long, Long> due = wheel.pollDue(1000L);

        assertThat(due).containsEntry(1L, 940L);
        assertThat(wheel.size()).isZero();
    }

    @Test
    void cancelRemovesTaskFromWheel() {
        TimeWheel wheel = new TimeWheel(60);
        wheel.schedule(1L, 1000L);
        wheel.schedule(2L, 1000L);

        wheel.cancel(1L);

        assertThat(wheel.size()).isEqualTo(1);
        assertThat(wheel.pollDue(1000L)).containsOnlyKeys(2L);
    }

    @Test
    void rescheduledTaskFiresAtNewTime() {
        TimeWheel wheel = new TimeWheel(60);
        wheel.schedule(1L, 1000L);
        assertThat(wheel.pollDue(1000L)).containsOnlyKeys(1L);

        wheel.schedule(1L, 1060L);

        assertThat(wheel.pollDue(1060L)).containsOnlyKeys(1L);
    }

    @Test
    void differentSecondsUseDifferentSlots() {
        TimeWheel wheel = new TimeWheel(60);
        wheel.schedule(1L, 1000L);
        wheel.schedule(2L, 1001L);

        assertThat(wheel.pollDue(1000L)).containsOnlyKeys(1L);
        assertThat(wheel.pollDue(1001L)).containsOnlyKeys(2L);
    }

    @Test
    void reschedulingToAnotherSlotRemovesTheOldEntry() {
        TimeWheel wheel = new TimeWheel(60);
        // 1000 % 60 = 40，1005 % 60 = 45：落在不同槽位。
        wheel.schedule(1L, 1000L);
        wheel.schedule(1L, 1005L);

        // 旧时间点不能再吐出任务 1，否则会多触发一次。
        assertThat(wheel.pollDue(1000L)).isEmpty();
        // 新时间点恰好触发一次。
        assertThat(wheel.pollDue(1005L)).containsOnlyKeys(1L);
        assertThat(wheel.pollDue(1005L)).isEmpty();
        assertThat(wheel.size()).isZero();
    }

    @Test
    void reschedulingToAnotherSlotKeepsSingleEntryInWheel() {
        TimeWheel wheel = new TimeWheel(60);
        wheel.schedule(1L, 1000L);
        wheel.schedule(1L, 1005L);
        wheel.schedule(1L, 1010L);

        // 轮子里只能有一个任务 1，否则两个条目各自推进会把触发频率翻倍。
        assertThat(wheel.size()).isEqualTo(1);
        assertThat(wheel.scheduledTimeOf(1L)).isEqualTo(1010L);
    }

    @Test
    void cancelAfterRescheduleStillRemovesTheTask() {
        TimeWheel wheel = new TimeWheel(60);
        wheel.schedule(1L, 1000L);
        wheel.schedule(1L, 1005L);

        wheel.cancel(1L);

        assertThat(wheel.size()).isZero();
        assertThat(wheel.pollDue(1000L)).isEmpty();
        assertThat(wheel.pollDue(1005L)).isEmpty();
    }

    @Test
    void concurrentAccessDoesNotCorruptTheWheel() throws Exception {
        // 时间轮线程、Web 线程（事务提交后的事件）、选举线程会并发访问时间轮。
        TimeWheel wheel = new TimeWheel(60);
        int workers = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < workers; i++) {
            long base = 1000L + i;
            Thread thread = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    for (int round = 0; round < 200; round++) {
                        wheel.schedule(1L, base + round);
                        wheel.scheduledTimeOf(1L);
                        wheel.pollDue(base + round);
                        wheel.cancel(2L);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(failure.get()).isNull();
        // 同一个任务不管被重排多少次，轮子里最多只有一条。
        assertThat(wheel.size()).isLessThanOrEqualTo(1);
    }
}
