package com.taskpilot.scheduler.leader;

import com.taskpilot.scheduler.config.SchedulerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderElectionServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private RAtomicLong atomicLong;

    @Mock
    private LeaderStateService leaderStateService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SchedulerProperties properties;
    private LeaderElectionService service;

    @BeforeEach
    void setUp() {
        properties = new SchedulerProperties();
        service = new LeaderElectionService(redissonClient, leaderStateService, eventPublisher, properties);
    }

    @Test
    void becomesLeaderWhenLockAcquiredAndClaimAccepted() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);
        when(atomicLong.incrementAndGet()).thenReturn(5L);
        when(leaderStateService.currentToken()).thenReturn(3L);
        when(leaderStateService.claim(anyString(), anyLong())).thenReturn(true);

        assertThat(service.campaign()).isTrue();
        assertThat(service.isLeader()).isTrue();
        assertThat(service.currentContext().fencingToken()).isEqualTo(5L);
        verify(eventPublisher).publishEvent(any(LeaderChangedEvent.class));
    }

    @Test
    void staysFollowerWhenLockHeldByAnotherInstance() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);

        assertThat(service.campaign()).isFalse();
        assertThat(service.isLeader()).isFalse();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void givesUpWhenClaimRejectedByStaleToken() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);
        when(atomicLong.incrementAndGet()).thenReturn(2L);
        when(leaderStateService.currentToken()).thenReturn(0L);
        when(leaderStateService.claim(anyString(), anyLong())).thenReturn(false);

        assertThat(service.campaign()).isFalse();
        assertThat(service.isLeader()).isFalse();
        verify(lock).unlock();
    }

    @Test
    void tokenFallsBackToDbTokenWhenRedisCounterResets() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);
        // Redis 重启后计数器归零。
        when(atomicLong.incrementAndGet()).thenReturn(1L);
        when(leaderStateService.currentToken()).thenReturn(9L);
        when(leaderStateService.claim(anyString(), anyLong())).thenReturn(true);

        service.campaign();

        verify(leaderStateService).claim(anyString(), eq(10L));
    }

    @Test
    void singleInstanceModeActsAsLeaderWithoutFencing() {
        properties.getElection().setEnabled(false);

        assertThat(service.campaign()).isTrue();
        assertThat(service.isLeader()).isTrue();
        assertThat(service.currentContext().fenced()).isFalse();
        verifyNoInteractions(redissonClient);
        verify(leaderStateService, never()).claim(anyString(), anyLong());
    }

    @Test
    void shutdownForceUnlocksLockHeldByCampaignThread() {
        // 抢锁在 campaign 线程，Spring 关闭线程上 isHeldByCurrentThread() 恒 false。
        // 必须强制释放，否则别的实例要等 watchdog 超时（约 30s）才能接管。
        becomeLeader();
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(lock.isHeldByThread(anyLong())).thenReturn(true);

        service.shutdown();

        verify(lock).forceUnlock();
        assertThat(service.isLeader()).isFalse();
    }

    @Test
    void shutdownDoesNotReleaseLockOwnedByAnotherInstance() {
        // 锁已经被别的实例拿走：不能 forceUnlock，否则会放出双 leader。
        becomeLeader();
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(lock.isHeldByThread(anyLong())).thenReturn(false);

        service.shutdown();

        verify(lock, never()).forceUnlock();
        verify(lock, never()).unlock();
    }

    @Test
    void shutdownUsesNormalUnlockWhenCalledOnTheLockOwnerThread() {
        becomeLeader();
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        service.shutdown();

        verify(lock).unlock();
        verify(lock, never()).forceUnlock();
    }

    @Test
    void electionUsesWatchdogWithoutFixedLease() throws Exception {
        // 死配置 lease-seconds 已删：抢锁必须走无参 tryLock()，让 watchdog 自动续期。
        // 改成 tryLock(leaseTime) 会关掉续期，活着的 leader 也会在租约到期后丢锁。
        becomeLeader();

        verify(lock).tryLock();
        verify(lock, never()).tryLock(anyLong(), any(TimeUnit.class));
    }

    @Test
    void electionConfigDoesNotExposeUnusedLeaseSeconds() {
        assertThat(Arrays.stream(SchedulerProperties.Election.class.getDeclaredFields())
                .map(Field::getName)
                .toList())
                .doesNotContain("leaseSeconds");
    }

    private void becomeLeader() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);
        when(atomicLong.incrementAndGet()).thenReturn(5L);
        when(leaderStateService.currentToken()).thenReturn(3L);
        when(leaderStateService.claim(anyString(), anyLong())).thenReturn(true);
        assertThat(service.campaign()).isTrue();
    }
}
