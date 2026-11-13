package com.taskpilot.scheduler.leader;

import com.taskpilot.scheduler.entity.LeaderEntity;
import com.taskpilot.scheduler.mapper.LeaderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * leader 状态的 DB 侧操作，也就是 fencing token 的落地点。
 * <p>分布式锁只能保证"同一时刻只有一个 leader"，但 leader 崩溃或网络分区时，
 * 旧 leader 可能还认为自己持有锁（脑裂）。fencing 的做法是：每次拿到 leader 身份
 * 领一个单调递增的 token，所有写入都要先过 token 校验，token 落后的一方一律拒绝。</p>
 */
@Service
public class LeaderStateService {

    private static final Logger log = LoggerFactory.getLogger(LeaderStateService.class);
    private static final int LEADER_ROW_ID = 1;

    private final LeaderMapper leaderMapper;

    public LeaderStateService(LeaderMapper leaderMapper) {
        this.leaderMapper = leaderMapper;
    }

    /**
     * 用 token 抢占 leader 身份。库里已有更大的 token 时会被拒绝（说明已经有更新的 leader）。
     */
    public boolean claim(String instanceId, long token) {
        LocalDateTime now = LocalDateTime.now();
        int updated = leaderMapper.claim(instanceId, token, now);
        if (updated > 0) {
            return true;
        }
        try {
            LeaderEntity entity = new LeaderEntity();
            entity.setId(LEADER_ROW_ID);
            entity.setInstanceId(instanceId);
            entity.setFencingToken(token);
            entity.setLeaderTime(now);
            entity.setFenceSeq(0L);
            leaderMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            // 别的实例刚建好这一行，说明它已经是 leader。
            return false;
        }
    }

    /**
     * fencing 校验：token 有效返回 true；库里已经是更新的 token 说明本实例已过期，返回 false。
     */
    public boolean checkFence(long token) {
        try {
            return leaderMapper.checkFence(token) > 0;
        } catch (Exception e) {
            log.error("fencing check failed, token={}", token, e);
            return false;
        }
    }

    /**
     * 库里记录的 token。用于和 Redis 的计数器取较大值，防止 Redis 重启后 token 倒退。
     */
    public long currentToken() {
        LeaderEntity entity = leaderMapper.selectById(LEADER_ROW_ID);
        return entity == null || entity.getFencingToken() == null ? 0L : entity.getFencingToken();
    }
}
