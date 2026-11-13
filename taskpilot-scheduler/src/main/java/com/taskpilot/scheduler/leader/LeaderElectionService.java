package com.taskpilot.scheduler.leader;

import com.taskpilot.scheduler.config.SchedulerProperties;
import jakarta.annotation.PreDestroy;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 领导选举：基于 Redisson 分布式锁选出唯一 leader，并用 fencing token 防止脑裂。
 * <p>流程：</p>
 * <ol>
 *   <li>每个实例启动后不停尝试抢锁（tryLock 立即返回，不阻塞）；</li>
 *   <li>抢到锁后领一个单调递增的 token（Redis 计数器与 DB 记录取较大值），写入 tp_leader；</li>
 *   <li>DB 侧写入被更大的 token 拒绝时，说明有更新的 leader，本次放弃；</li>
 *   <li>锁由 Redisson watchdog 自动续期；实例崩溃后锁超时释放，其他实例接管。</li>
 * </ol>
 * <p>关键点：锁只解决"同一时刻只有一个 leader"，fencing token 才解决"旧 leader 复活后继续写"。</p>
 */
@Service
public class LeaderElectionService {

    private static final Logger log = LoggerFactory.getLogger(LeaderElectionService.class);

    private final RedissonClient redissonClient;
    private final LeaderStateService leaderStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final SchedulerProperties properties;

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicReference<Long> leaderToken = new AtomicReference<>(null);
    private volatile boolean leader = false;
    private volatile RLock lock;
    private ScheduledExecutorService campaignExecutor;

    public LeaderElectionService(RedissonClient redissonClient,
                                 LeaderStateService leaderStateService,
                                 ApplicationEventPublisher eventPublisher,
                                 SchedulerProperties properties) {
        this.redissonClient = redissonClient;
        this.leaderStateService = leaderStateService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        long interval = properties.getElection().getRetryIntervalMs();
        campaignExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "taskpilot-election");
            thread.setDaemon(true);
            return thread;
        });
        campaignExecutor.scheduleAtFixedRate(this::campaignSafely, 0, interval, TimeUnit.MILLISECONDS);
        log.info("leader election started: instanceId={}, interval={}ms", instanceId, interval);
    }

    public String instanceId() {
        return instanceId;
    }

    public boolean isLeader() {
        return leader;
    }

    /**
     * 当前 leader 身份。token 用于写入前的 fencing 校验。
     */
    public LeaderContext currentContext() {
        Long token = leaderToken.get();
        return token == null ? LeaderContext.none() : new LeaderContext(token, instanceId);
    }

    /**
     * 竞选一轮。返回是否成功拿到 leader。
     */
    public boolean campaign() {
        if (!properties.getElection().isEnabled()) {
            // 单机模式：没有竞争者，直接把自己当成 leader，也不参与 fencing。
            if (!leader) {
                leader = true;
                leaderToken.set(null);
                publishChanged(true, null);
            }
            return true;
        }

        if (leader) {
            // 已经是 leader，先确认锁还在（watchdog 续期失败时会丢锁）。
            if (lock != null && lock.isHeldByCurrentThread()) {
                return true;
            }
            log.warn("lost leader lock, instanceId={}", instanceId);
            becomeFollower();
        }

        if (lock == null) {
            lock = redissonClient.getLock(properties.getElection().getLockKey());
        }
        boolean acquired;
        try {
            acquired = lock.tryLock();
        } catch (Exception e) {
            log.warn("try leader lock failed: {}", e.getMessage());
            return false;
        }
        if (!acquired) {
            return false;
        }

        long token = nextToken();
        if (!leaderStateService.claim(instanceId, token)) {
            // DB 里已经有更大的 token：有更新的 leader，放手。
            log.info("claim leadership rejected (stale token), instanceId={}, token={}", instanceId, token);
            unlockAcquiredLock();
            return false;
        }

        leader = true;
        leaderToken.set(token);
        log.info("became leader: instanceId={}, fencingToken={}", instanceId, token);
        publishChanged(true, token);
        return true;
    }

    private void campaignSafely() {
        try {
            campaign();
        } catch (Exception e) {
            log.error("leader campaign failed: {}", e.getMessage());
        }
    }

    private long nextToken() {
        long redisToken = 0L;
        try {
            RAtomicLong counter = redissonClient.getAtomicLong(properties.getElection().getTokenKey());
            redisToken = counter.incrementAndGet();
        } catch (Exception e) {
            log.warn("read fencing token counter failed, fallback to db token: {}", e.getMessage());
        }
        // 与 DB 里的 token 取较大值再 +1：Redis 重启导致计数器归零时也不会出现 token 倒退。
        long dbToken = leaderStateService.currentToken();
        return Math.max(redisToken, dbToken + 1);
    }

    private void becomeFollower() {
        leader = false;
        leaderToken.set(null);
        publishChanged(false, null);
    }

    private void publishChanged(boolean isLeader, Long token) {
        eventPublisher.publishEvent(new LeaderChangedEvent(isLeader, token));
    }

    private void safeUnlock() {
        try {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.debug("unlock failed: {}", e.getMessage());
        }
    }

    /**
     * 释放刚刚拿到手的锁（抢锁成功但没拿到 leader 身份时用）。
     */
    private void unlockAcquiredLock() {
        try {
            if (lock != null) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.debug("unlock failed: {}", e.getMessage());
        }
    }

    /**
     * 主动让位：正常退出时释放锁，其他实例可以立刻接管，不用等 watchdog 超时。
     */
    @PreDestroy
    public void shutdown() {
        if (campaignExecutor != null) {
            campaignExecutor.shutdownNow();
        }
        if (leader) {
            log.info("release leader lock: instanceId={}", instanceId);
        }
        safeUnlock();
    }
}
