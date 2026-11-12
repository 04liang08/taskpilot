package com.taskpilot.scheduler.schedule;

import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.scheduler.config.SchedulerProperties;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.service.TaskService;
import com.taskpilot.scheduler.service.TriggerService;
import com.taskpilot.scheduler.support.CronService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 时间轮调度器：自己实现的秒级时间轮，负责 cron 定时触发。
 * <p>要点：</p>
 * <ul>
 *   <li>DB 是真相源：任务定义和 next_trigger_time 都在 tp_task，时间轮只是内存索引；</li>
 *   <li>只扫描当前槽位，不做每秒全表扫描；</li>
 *   <li>触发动作丢到独立线程池，时间轮线程不被长任务拖住；</li>
 *   <li>错过（进程停过）的触发不补跑，直接跳到下一个未来时间。</li>
 * </ul>
 * <p>第 2 周后半段会在此基础上加领导选举，保证集群里只有 leader 在推轮子。</p>
 */
@Component
public class TriggerScheduler {

    private static final Logger log = LoggerFactory.getLogger(TriggerScheduler.class);
    private static final ZoneId ZONE = CronService.ZONE;

    private final TaskService taskService;
    private final TriggerService triggerService;
    private final CronService cronService;
    private final TimeWheel wheel;
    private final long tickMillis;
    private final int maxCatchUpSeconds;

    private final Map<Long, ScheduledTask> cache = new ConcurrentHashMap<>();
    private final AtomicLong lastTickSecond = new AtomicLong();
    private ScheduledExecutorService ticker;

    public TriggerScheduler(TaskService taskService,
                            TriggerService triggerService,
                            CronService cronService,
                            SchedulerProperties properties) {
        this.taskService = taskService;
        this.triggerService = triggerService;
        this.cronService = cronService;
        this.wheel = new TimeWheel(properties.getWheelSize());
        this.tickMillis = properties.getTickMillis();
        this.maxCatchUpSeconds = properties.getMaxCatchUpSeconds();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        loadTasks();
        startTicker();
    }

    /**
     * 从 DB 装载可调度任务，重建时间轮。重启补偿依赖这里。
     */
    public void loadTasks() {
        for (TaskEntity task : taskService.listSchedulable()) {
            scheduleTask(task);
        }
        log.info("time wheel loaded: {} task(s), wheelSize={}", cache.size(), wheel.getWheelSize());
    }

    /**
     * 任务定义变化后重排。放在事务提交后执行，避免读到未提交的数据。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskChanged(TaskScheduleEvent event) {
        Long taskId = event.taskId();
        if (event.deleted()) {
            remove(taskId);
            return;
        }
        TaskEntity task;
        try {
            task = taskService.get(taskId);
        } catch (BizException e) {
            remove(taskId);
            return;
        }
        if (task.getStatus() != TaskStatus.ENABLED || task.getTriggerType() != TriggerType.CRON) {
            remove(taskId);
            return;
        }
        scheduleTask(task);
    }

    /**
     * 推进一轮。nowEpochSecond 由外部传入，便于测试和补偿调用。
     */
    public void tick(long nowEpochSecond) {
        long last = lastTickSecond.getAndSet(nowEpochSecond);
        if (last <= 0) {
            // 第一轮只记录基准时间。
            return;
        }
        long from = last + 1;
        if (nowEpochSecond - last > maxCatchUpSeconds) {
            from = nowEpochSecond;
            log.warn("time wheel lagged {}s, skip catch-up", nowEpochSecond - last);
        }
        for (long second = from; second <= nowEpochSecond; second++) {
            Map<Long, Long> due = wheel.pollDue(second);
            for (Map.Entry<Long, Long> entry : due.entrySet()) {
                fire(entry.getKey(), entry.getValue());
            }
        }
    }

    private void startTicker() {
        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "taskpilot-wheel");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleAtFixedRate(this::tickSafely, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
        log.info("time wheel ticker started: tick={}ms", tickMillis);
    }

    private void tickSafely() {
        try {
            tick(currentEpochSecond());
        } catch (Exception e) {
            log.error("time wheel tick failed", e);
        }
    }

    private void fire(Long taskId, long scheduledEpochSecond) {
        ScheduledTask scheduled = cache.get(taskId);
        if (scheduled == null) {
            // 任务已停用或删除，不再触发。
            return;
        }
        triggerService.triggerAsync(taskId);

        LocalDateTime next = cronService.nextTimeAfter(
                scheduled.cronExpression(), toLocalDateTime(scheduledEpochSecond));
        if (next == null) {
            log.warn("cron will never fire again: taskId={}, cron={}", taskId, scheduled.cronExpression());
            cache.remove(taskId);
            return;
        }
        wheel.schedule(taskId, toEpochSecond(next));
        taskService.updateNextTriggerTime(taskId, next);
    }

    private void scheduleTask(TaskEntity task) {
        LocalDateTime base = LocalDateTime.now(ZONE);
        LocalDateTime stored = task.getNextTriggerTime();
        // 持久化的下次触发时间还在未来就沿用；已经过期说明中间停过（错过了），直接跳到下一个未来时间，不补跑。
        LocalDateTime next = stored != null && stored.isAfter(base)
                ? stored
                : cronService.nextTimeAfter(task.getCronExpression(), base);
        if (next == null) {
            log.warn("task has no next trigger time: taskId={}, cron={}", task.getId(), task.getCronExpression());
            remove(task.getId());
            return;
        }
        cache.put(task.getId(), new ScheduledTask(task.getId(), task.getTaskName(), task.getCronExpression()));
        wheel.schedule(task.getId(), toEpochSecond(next));
        taskService.updateNextTriggerTime(task.getId(), next);
    }

    private void remove(Long taskId) {
        wheel.cancel(taskId);
        cache.remove(taskId);
    }

    private long currentEpochSecond() {
        return System.currentTimeMillis() / 1000L;
    }

    private long toEpochSecond(LocalDateTime time) {
        return time.atZone(ZONE).toEpochSecond();
    }

    private LocalDateTime toLocalDateTime(long epochSecond) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZONE);
    }

    @PreDestroy
    public void shutdown() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
    }
}
