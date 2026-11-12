package com.taskpilot.scheduler.schedule;

import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.scheduler.config.SchedulerProperties;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.service.TaskService;
import com.taskpilot.scheduler.service.TriggerService;
import com.taskpilot.scheduler.support.CronService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriggerSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CRON = "*/10 * * * * *";

    @Mock
    private TaskService taskService;

    @Mock
    private TriggerService triggerService;

    @Mock
    private CronService cronService;

    private TriggerScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TriggerScheduler(taskService, triggerService, cronService, new SchedulerProperties());
    }

    @Test
    void loadTasksKeepsPersistedNextTriggerTime() {
        LocalDateTime next = LocalDateTime.now(ZONE).plusSeconds(30);
        TaskEntity task = task(1L, next);
        when(taskService.listSchedulable()).thenReturn(List.of(task));

        scheduler.loadTasks();

        verify(taskService).updateNextTriggerTime(eq(1L), eq(next));
        verify(cronService, never()).nextTimeAfter(anyString(), any());
    }

    @Test
    void loadTasksRecomputesWhenPersistedTimeAlreadyPassed() {
        LocalDateTime expired = LocalDateTime.now(ZONE).minusMinutes(5);
        LocalDateTime recomputed = LocalDateTime.now(ZONE).plusSeconds(15);
        TaskEntity task = task(1L, expired);
        when(taskService.listSchedulable()).thenReturn(List.of(task));
        when(cronService.nextTimeAfter(eq(CRON), any())).thenReturn(recomputed);

        scheduler.loadTasks();

        verify(taskService).updateNextTriggerTime(eq(1L), eq(recomputed));
    }

    @Test
    void tickFiresDueTaskAndReschedulesIt() {
        LocalDateTime due = LocalDateTime.now(ZONE).plusSeconds(5);
        LocalDateTime next = due.plusSeconds(10);
        TaskEntity task = task(1L, due);
        when(taskService.listSchedulable()).thenReturn(List.of(task));
        when(cronService.nextTimeAfter(eq(CRON), any())).thenReturn(next);

        scheduler.loadTasks();
        long dueEpoch = due.atZone(ZONE).toEpochSecond();
        scheduler.tick(dueEpoch - 1);
        scheduler.tick(dueEpoch);

        verify(triggerService).triggerAsync(1L);
        verify(taskService).updateNextTriggerTime(eq(1L), eq(next));
    }

    @Test
    void disabledTaskIsRemovedFromWheel() {
        LocalDateTime due = LocalDateTime.now(ZONE).plusSeconds(5);
        TaskEntity task = task(1L, due);
        task.setStatus(TaskStatus.DISABLED);
        when(taskService.get(1L)).thenReturn(task);

        scheduler.onTaskChanged(new TaskScheduleEvent(1L, false));
        long dueEpoch = due.atZone(ZONE).toEpochSecond();
        scheduler.tick(dueEpoch - 1);
        scheduler.tick(dueEpoch);

        verify(triggerService, never()).triggerAsync(any());
    }

    @Test
    void deletedTaskIsRemovedFromWheel() {
        LocalDateTime due = LocalDateTime.now(ZONE).plusSeconds(5);
        TaskEntity task = task(1L, due);
        when(taskService.listSchedulable()).thenReturn(List.of(task));
        scheduler.loadTasks();

        scheduler.onTaskChanged(new TaskScheduleEvent(1L, true));
        long dueEpoch = due.atZone(ZONE).toEpochSecond();
        scheduler.tick(dueEpoch - 1);
        scheduler.tick(dueEpoch);

        verify(triggerService, never()).triggerAsync(any());
    }

    private TaskEntity task(Long id, LocalDateTime nextTriggerTime) {
        TaskEntity task = new TaskEntity();
        task.setId(id);
        task.setTaskName("demo-cron");
        task.setCronExpression(CRON);
        task.setTriggerType(TriggerType.CRON);
        task.setStatus(TaskStatus.ENABLED);
        task.setTimeoutSeconds(30);
        task.setNextTriggerTime(nextTriggerTime);
        return task;
    }
}
