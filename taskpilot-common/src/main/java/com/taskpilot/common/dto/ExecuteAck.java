package com.taskpilot.common.dto;

/**
 * 执行器对"执行请求"的受理回执。
 * <p>第 4 周起执行接口只回 ACK，不等执行结果：真正的结果走 MQ 异步回传。
 * 这样调度中心的 dispatch 只花一次快回包的时间，长任务不会把调度线程占住。</p>
 *
 * @param accepted     是否受理
 * @param rejectReason 拒绝原因（受理时为 null）
 */
public record ExecuteAck(
        boolean accepted,
        String rejectReason) {

    public static ExecuteAck ok() {
        return new ExecuteAck(true, null);
    }

    public static ExecuteAck rejected(String reason) {
        return new ExecuteAck(false, reason);
    }
}
