package com.taskpilot.common.mq;

import com.taskpilot.common.dto.AlertMessage;

/**
 * 告警发布通道。
 * <p>保留接口的理由和 {@link ResultChannel} 一样：单测可替换，将来换消息中间件只改一处。</p>
 */
public interface AlertChannel {

    /**
     * 广播一条告警。实现方不能静默丢弃：告警丢了就没人知道出事了。
     */
    void publish(AlertMessage message);
}
