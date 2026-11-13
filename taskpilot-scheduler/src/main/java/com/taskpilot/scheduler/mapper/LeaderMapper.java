package com.taskpilot.scheduler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskpilot.scheduler.entity.LeaderEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface LeaderMapper extends BaseMapper<LeaderEntity> {

    /**
     * 尝试成为 leader：只有当前记录的 token 不大于自己的 token 时才允许覆盖。
     * <p>fence_seq 自增保证每次都会改动行，避免 MySQL 对"值未变化"返回 0 影响行数。</p>
     */
    @Update("""
            UPDATE tp_leader
               SET instance_id = #{instanceId},
                   fencing_token = #{token},
                   leader_time = #{leaderTime},
                   fence_check_time = NOW(3),
                   fence_seq = fence_seq + 1
             WHERE id = 1
               AND fencing_token <= #{token}
            """)
    int claim(@Param("instanceId") String instanceId,
              @Param("token") long token,
              @Param("leaderTime") LocalDateTime leaderTime);

    /**
     * fencing 校验：token 比库里记录的旧，说明已经有更新的 leader，本次写入必须被拒绝。
     */
    @Update("""
            UPDATE tp_leader
               SET fence_check_time = NOW(3),
                   fence_seq = fence_seq + 1
             WHERE id = 1
               AND fencing_token <= #{token}
            """)
    int checkFence(@Param("token") long token);
}
