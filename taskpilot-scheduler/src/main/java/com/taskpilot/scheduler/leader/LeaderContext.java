package com.taskpilot.scheduler.leader;

/**
 * 当前 leader 的身份信息。随触发链路往下传，用于给执行记录打上"谁触发的"标记。
 *
 * @param fencingToken leader 的 fencing token；null 表示不参与 fencing 校验（手动触发 / 单机模式）
 * @param instanceId   leader 实例标识
 */
public record LeaderContext(Long fencingToken, String instanceId) {

    public static LeaderContext none() {
        return new LeaderContext(null, null);
    }

    public boolean fenced() {
        return fencingToken != null;
    }
}
