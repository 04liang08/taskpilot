package com.taskpilot.scheduler.schedule;

import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.enums.TriggerType;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.scheduler.config.SchedulerProperties;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.leader.LeaderChangedEvent;
import com.taskpilot.scheduler.leader.LeaderContext;
import com.taskpilot.scheduler.leader.LeaderElectionService;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final LeaderElectionService leaderElectionService;
    private final TimeWheel wheel;
    private final long tickMillis;
    private final int maxCatchUpSeconds;
    private final MisfirePolicy misfirePolicy;
    private final int misfireThresholdSeconds;
    private final long taskSyncIntervalMillis;

    private final Map<Long, ScheduledTask> cache = new ConcurrentHashMap<>();
    private final AtomicLong lastTickSecond = new AtomicLong();
    private final AtomicLong lastSyncSecond = new AtomicLong();
    private ScheduledExecutorService ticker;

    public TriggerScheduler(TaskService taskService,
                            TriggerService triggerService,
                            CronService cronService,
                            LeaderElectionService leaderElectionService,
                            SchedulerProperties properties) {
        this.taskService = taskService;
        this.triggerService = triggerService;
        this.cronService = cronService;
        this.leaderElectionService = leaderElectionService;
        this.wheel = new TimeWheel(properties.getWheelSize());
        this.tickMillis = properties.getTickMillis();
        this.maxCatchUpSeconds = properties.getMaxCatchUpSeconds();
        this.misfirePolicy = properties.getMisfirePolicy();
        this.misfireThresholdSeconds = properties.getMisfireThresholdSeconds();
        this.taskSyncIntervalMillis = properties.getTaskSyncIntervalMs();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // 只启动轮子；时间轮由 leader 身份事件触发装载（见 onLeaderChanged）。
        startTicker();
    }

    /**
     * 从 DB 装载可调度任务，重建时间轮。重启补偿依赖这里。
     * <p>只有 leader 才装载：follower 推轮子没有意义。</p>
     */
    public void loadTasks() {
        if (!leaderElectionService.isLeader()) {
            return;
        }
        for (TaskEntity task : taskService.listSchedulable()) {
            scheduleTask(task);
        }
        log.info("time wheel loaded: {} task(s), wheelSize={}", cache.size(), wheel.getWheelSize());
    }

    /**
     * 任务定义变化后重排。放在事务提交后执行，避免读到未提交的数据。
     * <p>注意：任务可能是在别的实例上创建的，本地事件只覆盖本地；
     * leader 靠 {@link #syncTasks()} 定期与 DB 对齐，才能拿到其他实例创建的任务。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskChanged(TaskScheduleEvent event) {
        Long taskId = event.taskId();
        if (event.deleted()) {
            remove(taskId);
            return;
        }
        if (!leaderElectionService.isLeader()) {
            // follower 不推轮子，等下次同步时由 leader 统一排。
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
     * 与 DB 对齐任务列表。
     * <p>leader 需要知道「所有」实例上创建的任务——任务事件只在本地进程内传播，
     * 所以这里定期全量对齐一次：DB 里新增的任务排进轮子，DB 里消失的任务移出轮子。</p>
     * <p>已经在轮子里且 cron 没变的任务不动，避免每轮重写 next_trigger_time 造成节奏漂移。</p>
     */
    public void syncTasks() {
        if (!leaderElectionService.isLeader()) {
            return;
        }
        List<TaskEntity> tasks = taskService.listSchedulable();
        Set<Long> seen = new HashSet<>();
        for (TaskEntity task : tasks) {
            seen.add(task.getId());
            ScheduledTask cached = cache.get(task.getId());
            Long scheduled = wheel.scheduledTimeOf(task.getId());
            if (cached != null && scheduled != null
                    && cached.cronExpression().equals(task.getCronExpression())) {
                // 已在轮子里且 cron 没变：保持原样，避免重置 next_trigger_time。
                continue;
            }
            scheduleTask(task);
        }
        for (Long taskId : new ArrayList<>(cache.keySet())) {
            if (!seen.contains(taskId)) {
                remove(taskId);
            }
        }
    }

    /**
     * leader 身份变化：拿到 leader 就按 DB 重建时间轮，失去 leader 就把轮子清空。
     * <p>重建是必须的：新 leader 必须以 DB 里的 next_trigger_time 为准，而不是沿用旧内存状态。</p>
     */
    @EventListener
    public void onLeaderChanged(LeaderChangedEvent event) {
        if (event.leader()) {
            log.info("became leader, reload time wheel: instanceId={}, token={}",
                    leaderElectionService.instanceId(), event.leaderToken());
            // 重新以当前时间为基准，避免把当 follower 期间的时间当成落后要回补。
            lastTickSecond.set(0);
            loadTasks();
        } else {
            log.info("lost leadership, stop time wheel: instanceId={}", leaderElectionService.instanceId());
            clearWheel();
        }
    }

    /**
     * 推进一轮。nowEpochSecond 由外部传入，便于测试和补偿调用。
     */
    public void tick(long nowEpochSecond) {
        if (!leaderElectionService.isLeader()) {
            // 非 leader 不推轮子：集群里只能有一个实例在触发。
            return;
        }
        long last = lastTickSecond.getAndSet(nowEpochSecond);
        // 第一轮（刚启动或刚拿到 leader）也要扫当前这一格：
        // 补跑任务落在过去的时间槽里，不扫就会一直等到该槽位再次轮转（最多 60 秒）。
        long from = last <= 0 ? nowEpochSecond : last + 1;
        if (last > 0 && nowEpochSecond - last > maxCatchUpSeconds) {
            from = nowEpochSecond;
            log.warn("time wheel lagged {}s, skip catch-up", nowEpochSecond - last);
        }
        for (long second = from; second <= nowEpochSecond; second++) {
            Map<Long, Long> due = wheel.pollDue(second);
            for (Map.Entry<Long, Long> entry : due.entrySet()) {
                fire(entry.getKey(), entry.getValue());
            }
        }
        maybeSyncTasks(nowEpochSecond);
    }

    /**
     * 定期与 DB 对齐任务列表（把其他实例上创建的任务收进来）。
     */
    private void maybeSyncTasks(long nowEpochSecond) {
        if (taskSyncIntervalMillis <= 0) {
            return;
        }
        long last = lastSyncSecond.get();
        if (last != 0 && nowEpochSecond - last < taskSyncIntervalMillis / 1000) {
            return;
        }
        lastSyncSecond.set(nowEpochSecond);
        try {
            syncTasks();
        } catch (Exception e) {
            log.error("sync tasks from db failed", e);
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
        if (!leaderElectionService.isLeader()) {
            return;
        }
        ScheduledTask scheduled = cache.get(taskId);
        if (scheduled == null) {
            // 任务已停用或删除，不再触发。
            return;
        }
        LeaderContext leaderContext = leaderElectionService.currentContext();
        LocalDateTime scheduledTime = toLocalDateTime(scheduledEpochSecond);
        triggerService.triggerAsync(taskId, leaderContext, scheduledTime);

        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDateTime next = cronService.nextTimeAfter(scheduled.cronExpression(), scheduledTime);
        if (next == null) {
            log.warn("cron will never fire again: taskId={}, cron={}", taskId, scheduled.cronExpression());
            cache.remove(taskId);
            return;
        }
        if (!next.isAfter(now)) {
            // 补跑场景：cron.next(计划时间) 仍落在过去，继续用它会连着补跑一串历史触发。
            // 直接推进到第一个未来时间，保证只补一次。
            next = cronService.nextTimeAfter(scheduled.cronExpression(), now);
            if (next == null) {
                cache.remove(taskId);
                return;
            }
        }
        wheel.schedule(taskId, toEpochSecond(next));
        taskService.updateNextTriggerTime(taskId, next);
    }

    private void scheduleTask(TaskEntity task) {
        LocalDateTime base = LocalDateTime.now(ZONE);
        LocalDateTime stored = task.getNextTriggerTime();
        boolean alreadyScheduled = cache.containsKey(task.getId());
        LocalDateTime next;
        if (stored != null && stored.isAfter(base)) {
            // 正常：沿用持久化的下次触发时间。
            next = stored;
        } else if (alreadyScheduled) {
            // 已经在轮子里：说明刚错过一次，直接推进到未来，不重复补跑。
            next = cronService.nextTimeAfter(task.getCronExpression(), base);
        } else if (stored != null && shouldCatchUp(stored, base)) {
            // 补跑：落后在阈值内，立即触发一次，然后把下次时间推进到未来。
            catchUpTask(task, stored, base);
            return;
        } else {
            // 跳过：错过的触发不补跑，直接推进到下一个未来时间。
            next = cronService.nextTimeAfter(task.getCronExpression(), base);
        }
        if (next == null) {
            log.warn("task has no next trigger time: taskId={}, cron={}", task.getId(), task.getCronExpression());
            remove(task.getId());
            return;
        }
        cache.put(task.getId(), new ScheduledTask(task.getId(), task.getTaskName(), task.getCronExpression()));
        wheel.schedule(task.getId(), toEpochSecond(next));
        taskService.updateNextTriggerTime(task.getId(), next);
    }

    /**
     * 补跑一次错过的触发，然后推进到下一个未来时间。
     * <p>立即触发而不是塞回过去的时间槽：过去的时间槽要等轮转回来（最多 60 秒）才会被扫到。</p>
     */
    private void catchUpTask(TaskEntity task, LocalDateTime scheduled, LocalDateTime now) {
        log.info("misfire catch-up: taskId={}, scheduled={}, now={}", task.getId(), scheduled, now);
        cache.put(task.getId(), new ScheduledTask(task.getId(), task.getTaskName(), task.getCronExpression()));
        triggerService.triggerAsync(task.getId(), leaderElectionService.currentContext(), scheduled);

        LocalDateTime next = cronService.nextTimeAfter(task.getCronExpression(), now);
        if (next == null) {
            cache.remove(task.getId());
            return;
        }
        wheel.schedule(task.getId(), toEpochSecond(next));
        taskService.updateNextTriggerTime(task.getId(), next);
    }

    /**
     * 是否补跑错过的触发：策略允许，且落后时间在阈值内。
     */
    private boolean shouldCatchUp(LocalDateTime scheduled, LocalDateTime now) {
        if (misfirePolicy != MisfirePolicy.FIRE_ONCE) {
            return false;
        }
        long lagSeconds = Duration.between(scheduled, now).getSeconds();
        return lagSeconds > 0 && lagSeconds <= misfireThresholdSeconds;
    }

    private void remove(Long taskId) {
        wheel.cancel(taskId);
        cache.remove(taskId);
    }

    /**
     * 清空时间轮与缓存（失去 leader 身份时调用）。
     */
    private void clearWheel() {
        wheel.clear();
        cache.clear();
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
