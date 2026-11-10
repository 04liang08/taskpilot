package com.taskpilot.executor.service;

import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.executor.handler.HandlerRegistry;
import com.taskpilot.executor.handler.TaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 在独立线程池中执行处理器，并做单次执行超时控制。
 * 超时后取消任务（interrupt），处理器应尽快响应中断。
 */
@Service
public class TaskExecuteService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecuteService.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private final HandlerRegistry handlerRegistry;
    private final ThreadPoolTaskExecutor executor;

    public TaskExecuteService(HandlerRegistry handlerRegistry,
                              @Qualifier("taskExecutorPool") ThreadPoolTaskExecutor executor) {
        this.handlerRegistry = handlerRegistry;
        this.executor = executor;
    }

    public ExecuteResult execute(ExecuteRequest request) {
        if (request == null) {
            return ExecuteResult.failure("执行请求为空", 0L);
        }
        int timeoutSeconds = resolveTimeoutSeconds(request.timeoutSeconds());
        long startNanos = System.nanoTime();

        Future<ExecuteResult> future;
        try {
            future = executor.submit(() -> runHandler(request));
        } catch (RejectedExecutionException e) {
            return ExecuteResult.failure("执行器线程池已满，任务被拒绝", elapsedMs(startNanos));
        }

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("task execution timeout: executionId={}, handler={}, timeout={}s",
                    request.executionId(), request.handlerName(), timeoutSeconds);
            return ExecuteResult.timeout("执行超时（" + timeoutSeconds + "s）", elapsedMs(startNanos));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ExecuteResult.failure("执行被中断", elapsedMs(startNanos));
        } catch (ExecutionException e) {
            return ExecuteResult.failure(rootMessage(e.getCause()), elapsedMs(startNanos));
        }
    }

    private ExecuteResult runHandler(ExecuteRequest request) {
        long startNanos = System.nanoTime();
        try {
            TaskHandler handler = handlerRegistry.require(request.handlerName());
            String summary = handler.execute(request);
            return ExecuteResult.success(summary == null ? "ok" : summary, elapsedMs(startNanos));
        } catch (InterruptedException e) {
            // 超时取消会中断执行线程，外层已经记录 timeout，这里不再重复打印堆栈。
            Thread.currentThread().interrupt();
            return ExecuteResult.failure("执行被中断", elapsedMs(startNanos));
        } catch (IllegalArgumentException e) {
            // 未注册处理器、参数不合法属于可预期错误，只记录原因。
            log.warn("handler rejected request: executionId={}, handler={}, reason={}",
                    request.executionId(), request.handlerName(), e.getMessage());
            return ExecuteResult.failure(rootMessage(e), elapsedMs(startNanos));
        } catch (Exception e) {
            log.warn("handler execution failed: executionId={}, handler={}",
                    request.executionId(), request.handlerName(), e);
            return ExecuteResult.failure(rootMessage(e), elapsedMs(startNanos));
        }
    }

    private int resolveTimeoutSeconds(Integer timeoutSeconds) {
        return timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }

    private long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
