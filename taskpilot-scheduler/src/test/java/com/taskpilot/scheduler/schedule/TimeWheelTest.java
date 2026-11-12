package com.taskpilot.scheduler.schedule;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
