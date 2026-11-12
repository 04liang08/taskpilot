package com.taskpilot.scheduler.service;

import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.common.exception.BizException;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.scheduler.client.ExecutorClient;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.entity.TaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手动触发编排：校验任务 -> 选择在线执行器 -> 落 RUNNING 记录 -> 调用执行器 -> 落终态。
 * <p>第 1 周同步调用、不重试；失败直接记录，重试在第 3 周实现。</p>
 */
@Service
public class TriggerService {

    private static final Logger log = LoggerFactory.getLogger(TriggerService.class);

    private final TaskService taskService;
    private final ExecutorService executorService;
    private final ExecutionService executionService;
    private final ExecutorClient executorClient;
    private final AtomicInteger roundRobin = new AtomicInteger();

    public TriggerService(TaskService taskService,
                          ExecutorService executorService,
                          ExecutionService executionService,
                          ExecutorClient executorClient) {
        this.taskService = taskService;
        this.executorService = executorService;
        this.executionService = executionService;
        this.executorClient = executorClient;
    }

    public ExecutionLogEntity trigger(Long taskId) {
        TaskEntity task = taskService.get(taskId);
        if (task.getStatus() != TaskStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "任务未启用，无法触发: " + taskId);
        }

        List<ExecutorEntity> onlineExecutors = executorService.listOnline();
        if (onlineExecutors.isEmpty()) {
            ExecutionLogEntity failed = executionService.createFailed(task, "没有可用的在线执行器");
            throw new BizException(ErrorCode.EXECUTOR_UNAVAILABLE,
                    "没有可用的在线执行器，执行记录 ID=" + failed.getId() + "（FAILED）");
        }

        ExecutorEntity executor = onlineExecutors.get(
                Math.floorMod(roundRobin.getAndIncrement(), onlineExecutors.size()));
        ExecutionLogEntity execution = executionService.createRunning(task, executor);
        taskService.markTriggered(taskId);

        ExecuteRequest request = new ExecuteRequest(
                execution.getId(),
                task.getId(),
                task.getHandlerName(),
                task.getHandlerParam(),
                0,
                task.getShardTotal() == null ? 1 : task.getShardTotal(),
                task.getTimeoutSeconds());

        try {
            ExecuteResult result = executorClient.execute(executor.getAddress(), request);
            ExecutionLogEntity finished = executionService.complete(execution, result);
            log.info("manual trigger finished: taskId={}, executionId={}, executor={}, status={}, durationMs={}",
                    taskId, execution.getId(), executor.getAddress(),
                    finished.getStatus(), finished.getDurationMs());
            return finished;
        } catch (ResourceAccessException e) {
            String message = "调用执行器失败: " + rootMessage(e);
            if (isTimeout(e)) {
                ExecutionLogEntity finished = executionService.markTimeout(execution, "调用执行器超时: " + rootMessage(e));
                log.warn("manual trigger timeout: taskId={}, executionId={}, executor={}, reason={}",
                        taskId, execution.getId(), executor.getAddress(), rootMessage(e));
                return finished;
            }
            ExecutionLogEntity finished = executionService.markFailed(execution, message);
            log.warn("manual trigger failed: taskId={}, executionId={}, executor={}, reason={}",
                    taskId, execution.getId(), executor.getAddress(), rootMessage(e));
            return finished;
        } catch (RestClientException e) {
            String message = "调用执行器失败: " + rootMessage(e);
            ExecutionLogEntity finished = executionService.markFailed(execution, message);
            log.warn("manual trigger failed: taskId={}, executionId={}, executor={}, reason={}",
                    taskId, execution.getId(), executor.getAddress(), rootMessage(e));
            return finished;
        } catch (Exception e) {
            String message = "触发异常: " + rootMessage(e);
            ExecutionLogEntity finished = executionService.markFailed(execution, message);
            log.error("manual trigger error: taskId={}, executionId={}", taskId, execution.getId(), e);
            return finished;
        }
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
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
