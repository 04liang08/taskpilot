package com.taskpilot.common.mq;

import com.taskpilot.common.dto.ExecutionResultMessage;

/**
 * 执行结果的发布通道。
 * <p>保留接口是为了两件事：一是单测可以替换实现、不依赖真实 broker；
 * 二是将来换消息中间件（Kafka / RocketMQ）时只改这一处。</p>
 */
public interface ResultChannel {

    /**
     * 发布一条执行结果。
     * <p>实现方要保证"要么消息确认落到 broker，要么明确失败"，
     * 不能静默丢弃——丢了结果，调度中心只能靠僵尸回收兜底。</p>
     */
    void publish(ExecutionResultMessage message);
}
