package com.taskpilot.scheduler.schedule;

import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.scheduler.config.SchedulerProperties;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.leader.LeaderChangedEvent;
import com.taskpilot.scheduler.leader.LeaderContext;
import com.taskpilot.scheduler.leader.LeaderElectionService;
import com.taskpilot.scheduler.service.TaskService;
import com.taskpilot.scheduler.service.TriggerService;
import com.taskpilot.scheduler.support.CronService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Mock
    private LeaderElectionService leaderElectionService;

    private TriggerScheduler scheduler;
    private SchedulerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SchedulerProperties();
        scheduler = buildScheduler();
    }

    private TriggerScheduler buildScheduler() {
        return new TriggerScheduler(taskService, triggerService, cronService,
                leaderElectionService, properties);
    }

    @Test
    void loadTasksKeepsPersistedNextTriggerTime() {
        LocalDateTime next = LocalDateTime.now(ZONE).plusSeconds(30);
        TaskEntity task = task(1L, next);
        when(taskService.listSchedulable()).thenReturn(List.of(task));
        when(leaderElectionService.isLeader()).thenReturn(true);

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
        when(leaderElectionService.isLeader()).thenReturn(true);

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
        when(leaderElectionService.isLeader()).thenReturn(true);
        when(leaderElectionService.currentContext())
                .thenReturn(new LeaderContext(7L, "inst-a"));

        scheduler.loadTasks();
        long dueEpoch = due.atZone(ZONE).toEpochSecond();
        scheduler.tick(dueEpoch - 1);
        scheduler.tick(dueEpoch);

        verify(triggerService).triggerAsync(eq(1L), eq(new LeaderContext(7L, "inst-a")));
        verify(taskService).updateNextTriggerTime(eq(1L), eq(next));
    }

    @Test
    void tickDoesNothingWhenNotLeader() {
        LocalDateTime due = LocalDateTime.now(ZONE).plusSeconds(5);
        when(leaderElectionService.isLeader()).thenReturn(false);

        long dueEpoch = due.atZone(ZONE).toEpochSecond();
        scheduler.tick(dueEpoch - 1);
        scheduler.tick(dueEpoch);

        verify(triggerService, never()).triggerAsync(any(), any());
    }

    @Test
    void losingLeadershipStopsTheWheel() {
        LocalDateTime due = LocalDateTime.now(ZONE).plusSeconds(5);
        TaskEntity task = task(1L, due);
        when(taskService.listSchedulable()).thenReturn(List.of(task));
        when(leaderElectionService.isLeader()).thenReturn(true);
        scheduler.loadTasks();

        scheduler.onLeaderChanged(new LeaderChangedEvent(false, null));
        when(leaderElectionService.isLeader()).thenReturn(false);
        long dueEpoch = due.atZone(ZONE).toEpochSecond();
        scheduler.tick(dueEpoch - 1);
        scheduler.tick(dueEpoch);

        verify(triggerService, never()).triggerAsync(any(), any());
    }

    @Test
    void disabledTaskIsRemovedFromWheel() {
        LocalDateTime due = LocalDateTime.now(ZONE).plusSeconds(5);
        TaskEntity task = task(1L, due);
        task.setStatus(TaskStatus.DISABLED);
        when(taskService.get(1L)).thenReturn(task);
        when(leaderElectionService.isLeader()).thenReturn(true);

        scheduler.onTaskChanged(new TaskScheduleEvent(1L, false));
        long dueEpoch = due.atZone(ZONE).toEpochSecond();
        scheduler.tick(dueEpoch - 1);
        scheduler.tick(dueEpoch);

        verify(triggerService, never()).triggerAsync(any(), any());
    }

    @Test
    void deletedTaskIsRemovedFromWheel() {
        LocalDateTime due = LocalDateTime.now(ZONE).plusSeconds(5);
        TaskEntity task = task(1L, due);
        when(taskService.listSchedulable()).thenReturn(List.of(task));
        when(leaderElectionService.isLeader()).thenReturn(true);
        scheduler.loadTasks();

        scheduler.onTaskChanged(new TaskScheduleEvent(1L, true));
        // 任务已删除：DB 里不再有它，定期同步也不会把它塞回轮子。
        when(taskService.listSchedulable()).thenReturn(List.of());
        long dueEpoch = due.atZone(ZONE).toEpochSecond();
        scheduler.tick(dueEpoch - 1);
        scheduler.tick(dueEpoch);

        verify(triggerService, never()).triggerAsync(any(), any());
    }

    @Test
    void misfireIsSkippedByDefault() {
        LocalDateTime expired = LocalDateTime.now(ZONE).minusSeconds(30);
        TaskEntity task = task(1L, expired);
        when(taskService.listSchedulable()).thenReturn(List.of(task));
        when(cronService.nextTimeAfter(eq(CRON), any())).thenAnswer(invocation -> {
            LocalDateTime from = invocation.getArgument(1);
            return from.plusSeconds(10);
        });
        when(leaderElectionService.isLeader()).thenReturn(true);

        scheduler.loadTasks();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService).updateNextTriggerTime(eq(1L), captor.capture());
        assertThat(captor.getValue()).isAfter(LocalDateTime.now(ZONE));
    }

    @Test
    void misfireIsCaughtUpOnceWhenPolicyAllows() {
        properties.setMisfirePolicy(MisfirePolicy.FIRE_ONCE);
        properties.setMisfireThresholdSeconds(300);
        scheduler = buildScheduler();
        LocalDateTime expired = LocalDateTime.now(ZONE).minusSeconds(30);
        TaskEntity task = task(1L, expired);
        when(taskService.listSchedulable()).thenReturn(List.of(task));
        when(cronService.nextTimeAfter(eq(CRON), any())).thenAnswer(invocation -> {
            LocalDateTime from = invocation.getArgument(1);
            return from.plusSeconds(10);
        });
        when(leaderElectionService.isLeader()).thenReturn(true);

        scheduler.loadTasks();

        // 补跑立即发生一次（不再等时间槽轮转回来）。
        verify(triggerService, times(1)).triggerAsync(eq(1L), any());
        // 并且把下次触发时间推进到未来，不会连着补一串。
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService, atLeastOnce()).updateNextTriggerTime(eq(1L), captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1))
                .isAfter(LocalDateTime.now(ZONE));
    }

    @Test
    void repeatedLoadDoesNotCatchUpTwice() {
        properties.setMisfirePolicy(MisfirePolicy.FIRE_ONCE);
        properties.setMisfireThresholdSeconds(300);
        scheduler = buildScheduler();
        LocalDateTime expired = LocalDateTime.now(ZONE).minusSeconds(30);
        TaskEntity task = task(1L, expired);
        when(taskService.listSchedulable()).thenReturn(List.of(task));
        when(cronService.nextTimeAfter(eq(CRON), any())).thenAnswer(invocation -> {
            LocalDateTime from = invocation.getArgument(1);
            return from.plusSeconds(10);
        });
        when(leaderElectionService.isLeader()).thenReturn(true);

        scheduler.loadTasks();
        scheduler.loadTasks();

        verify(triggerService, times(1)).triggerAsync(eq(1L), any());
    }

    @Test
    void misfireTooOldIsNotCaughtUp() {
        properties.setMisfirePolicy(MisfirePolicy.FIRE_ONCE);
        properties.setMisfireThresholdSeconds(300);
        scheduler = buildScheduler();
        LocalDateTime expired = LocalDateTime.now(ZONE).minusSeconds(3600);
        TaskEntity task = task(1L, expired);
        when(taskService.listSchedulable()).thenReturn(List.of(task));
        when(cronService.nextTimeAfter(eq(CRON), any())).thenAnswer(invocation -> {
            LocalDateTime from = invocation.getArgument(1);
            return from.plusSeconds(10);
        });
        when(leaderElectionService.isLeader()).thenReturn(true);

        scheduler.loadTasks();
        long expiredEpoch = expired.atZone(ZONE).toEpochSecond();
        scheduler.tick(expiredEpoch);
        scheduler.tick(expiredEpoch + 1);

        verify(triggerService, never()).triggerAsync(any(), any());
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
