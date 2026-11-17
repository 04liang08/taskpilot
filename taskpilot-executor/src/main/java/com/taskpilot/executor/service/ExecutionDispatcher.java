package com.taskpilot.executor.service;

import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.common.dto.ExecutionResultMessage;
import com.taskpilot.common.enums.ExecutionStatus;
import com.taskpilot.common.metrics.MetricNames;
import com.taskpilot.common.mq.ResultChannel;
import com.taskpilot.executor.config.ExecutorProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 执行调度：接住执行请求，在后台跑完处理器，再把结果发回调度中心。
 *
 * <p>第 4 周起执行接口只回 ACK，不等结果：
 * HTTP 线程把请求丢进调度线程池就返回，处理器在 {@code taskExecutorPool} 里跑，
 * 跑完由这里把结果发到 MQ。</p>
 *
 * <p>调度线程池满时直接拒绝（accepted=false）：调度中心据此把记录标成失败并重试，
 * 比悄悄排队、最后超时要好。</p>
 */
@Service
public class ExecutionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ExecutionDispatcher.class);

    private final ThreadPoolTaskExecutor dispatchExecutor;
    private final TaskExecuteService taskExecuteService;
    private final ResultChannel resultChannel;
    private final ExecutorProperties properties;
    private final MeterRegistry meterRegistry;

    public ExecutionDispatcher(@Qualifier("executionDispatchPool") ThreadPoolTaskExecutor dispatchExecutor,
                               TaskExecuteService taskExecuteService,
                               ResultChannel resultChannel,
                               ExecutorProperties properties,
                               MeterRegistry meterRegistry) {
        this.dispatchExecutor = dispatchExecutor;
        this.taskExecuteService = taskExecuteService;
        this.resultChannel = resultChannel;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 受理一个执行请求。
     *
     * @return true 表示已受理（结果稍后经 MQ 回传），false 表示执行器现在接不了
     */
    public boolean dispatch(ExecuteRequest request) {
        if (request == null) {
            return false;
        }
        try {
            dispatchExecutor.execute(() -> run(request));
            return true;
        } catch (TaskRejectedException e) {
            log.warn("execution rejected, dispatch pool is full: executionId={}", request.executionId());
            return false;
        }
    }

    private void run(ExecuteRequest request) {
        long startNanos = System.nanoTime();
        ExecuteResult result = taskExecuteService.execute(request);
        countExecution(request.handlerName(), statusOf(result));
        // 处理器环绕计时的业务真实耗时（handler 低基数枚举，ADR-022；
        // 与调度中心的 DistributionSummary 同名不同渲染，互不覆盖）。
        meterRegistry.timer(MetricNames.EXECUTION_DURATION, "handler", safeHandler(request.handlerName()))
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
        resultChannel.publish(toMessage(request, result));
    }

    /** 执行完成计数：status=success|failed|timeout + handler。 */
    private void countExecution(String handlerName, ExecutionStatus status) {
        Counter.builder(MetricNames.EXECUTIONS)
                .description("处理器执行完成次数")
                .tag("status", status.name().toLowerCase())
                .tag("handler", safeHandler(handlerName))
                .register(meterRegistry)
                .increment();
    }

    private static String safeHandler(String handlerName) {
        return handlerName == null || handlerName.isBlank() ? "unknown" : handlerName;
    }

    /**
     * 把执行结果转成上报消息。
     */
    ExecutionResultMessage toMessage(ExecuteRequest request, ExecuteResult result) {
        return new ExecutionResultMessage(
                request.executionId(),
                request.taskId(),
                statusOf(result),
                result.durationMs(),
                result.errorMessage(),
                result.resultSummary(),
                properties.getAddress(),
                LocalDateTime.now());
    }

    private ExecutionStatus statusOf(ExecuteResult result) {
        if (result.timeout()) {
            return ExecutionStatus.TIMEOUT;
        }
        return result.success() ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED;
    }
}
