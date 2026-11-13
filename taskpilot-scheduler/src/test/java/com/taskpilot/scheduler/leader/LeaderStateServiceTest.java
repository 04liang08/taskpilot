package com.taskpilot.scheduler.leader;

import com.taskpilot.scheduler.entity.LeaderEntity;
import com.taskpilot.scheduler.mapper.LeaderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderStateServiceTest {

    @Mock
    private LeaderMapper leaderMapper;

    @InjectMocks
    private LeaderStateService leaderStateService;

    @Test
    void claimUpdatesExistingLeaderRow() {
        when(leaderMapper.claim(anyString(), anyLong(), any())).thenReturn(1);

        assertThat(leaderStateService.claim("inst-a", 5L)).isTrue();
    }

    @Test
    void claimInsertsWhenLeaderRowMissing() {
        when(leaderMapper.claim(anyString(), anyLong(), any())).thenReturn(0);
        when(leaderMapper.insert(any(LeaderEntity.class))).thenReturn(1);

        assertThat(leaderStateService.claim("inst-a", 5L)).isTrue();
    }

    @Test
    void claimFailsWhenAnotherInstanceInsertedFirst() {
        when(leaderMapper.claim(anyString(), anyLong(), any())).thenReturn(0);
        when(leaderMapper.insert(any(LeaderEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThat(leaderStateService.claim("inst-a", 5L)).isFalse();
    }

    @Test
    void checkFenceAcceptsCurrentToken() {
        when(leaderMapper.checkFence(5L)).thenReturn(1);

        assertThat(leaderStateService.checkFence(5L)).isTrue();
    }

    @Test
    void checkFenceRejectsStaleToken() {
        when(leaderMapper.checkFence(5L)).thenReturn(0);

        assertThat(leaderStateService.checkFence(5L)).isFalse();
    }

    @Test
    void currentTokenFallsBackToZeroWhenNoLeaderRow() {
        when(leaderMapper.selectById(1)).thenReturn(null);

        assertThat(leaderStateService.currentToken()).isZero();
    }

    @Test
    void currentTokenReadsFromDb() {
        LeaderEntity entity = new LeaderEntity();
        entity.setId(1);
        entity.setFencingToken(9L);
        when(leaderMapper.selectById(1)).thenReturn(entity);

        assertThat(leaderStateService.currentToken()).isEqualTo(9L);
    }
}
