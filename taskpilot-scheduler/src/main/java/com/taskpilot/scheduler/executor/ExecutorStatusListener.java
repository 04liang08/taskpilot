package com.taskpilot.scheduler.executor;

import com.taskpilot.scheduler.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 执行器上下线事件的处理。
 * <p>当前实现：执行器下线时，把它身上还 RUNNING 的执行记录收尾成 FAILED。</p>
 *
 * <p><b>顺序很重要</b>：同一个下线事件上还有失败转移的监听器，
 * 必须先收尾再转移。收尾把 RUNNING 变成 FAILED 并挂上重试时间，
 * 转移才能用一条 UPDATE 原子地把它抢过来重试；
 * 反过来先转移的话，RUNNING 记录抢不动，就被永久丢掉了。</p>
 */
@Component
public class ExecutorStatusListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutorStatusListener.class);

    private final ExecutionService executionService;

    public ExecutorStatusListener(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
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
