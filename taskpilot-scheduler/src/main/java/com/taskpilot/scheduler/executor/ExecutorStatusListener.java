package com.taskpilot.scheduler.executor;

import com.taskpilot.scheduler.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 执行器上下线事件的处理。
 * <p>当前实现：执行器下线时，把它身上还 RUNNING 的执行记录收尾成 FAILED。
 * Week 3 Day 3 会在同一个事件上追加"分片重分配"。</p>
 */
@Component
public class ExecutorStatusListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutorStatusListener.class);

    private final ExecutionService executionService;

    public ExecutorStatusListener(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @EventListener
    public void onStatusChanged(ExecutorStatusChangedEvent event) {
        if (event.online()) {
            log.info("executor online: id={}, address={}", event.executorId(), event.address());
            return;
        }
        int affected = executionService.failRunningOfExecutor(
                event.executorId(), "执行器下线: " + event.address());
        log.warn("executor offline: id={}, address={}, 收尾 {} 条运行中的执行记录",
                event.executorId(), event.address(), affected);
    }
}
