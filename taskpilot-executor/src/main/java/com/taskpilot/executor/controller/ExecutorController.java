package com.taskpilot.executor.controller;

import com.taskpilot.common.api.ApiResponse;
import com.taskpilot.common.dto.ExecuteAck;
import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.executor.handler.HandlerRegistry;
import com.taskpilot.executor.service.ExecutionDispatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 执行器内部接口，供调度中心调用。
 * <p>第 4 周起 {@code /execute} 只回 ACK（受理与否），不等待执行结果：
 * 结果由执行器跑完后经 RabbitMQ 异步回传，调度中心的 dispatch 因此是"快回包"的。</p>
 */
@RestController
@RequestMapping("/api/v1/executor")
public class ExecutorController {

    private final ExecutionDispatcher executionDispatcher;
    private final HandlerRegistry handlerRegistry;

    public ExecutorController(ExecutionDispatcher executionDispatcher, HandlerRegistry handlerRegistry) {
        this.executionDispatcher = executionDispatcher;
        this.handlerRegistry = handlerRegistry;
    }

    @PostMapping("/execute")
    public ExecuteAck execute(@RequestBody ExecuteRequest request) {
        if (executionDispatcher.dispatch(request)) {
            return ExecuteAck.ok();
        }
        return ExecuteAck.rejected("执行器调度队列已满，请稍后重试");
    }

    @GetMapping("/handlers")
    public ApiResponse<List<String>> handlers() {
        return ApiResponse.ok(List.copyOf(handlerRegistry.names()));
    }
}
