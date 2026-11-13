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
}
