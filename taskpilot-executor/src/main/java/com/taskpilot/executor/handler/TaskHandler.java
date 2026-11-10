package com.taskpilot.executor.handler;

import com.taskpilot.common.dto.ExecuteRequest;

/**
 * 任务处理器。每个处理器是一个 Spring Bean，通过 {@link #name()} 暴露给调度中心。
 * <p>返回值作为执行结果的摘要；抛出异常表示执行失败。</p>
 */
public interface TaskHandler {

    String name();

    String execute(ExecuteRequest request) throws Exception;
}
