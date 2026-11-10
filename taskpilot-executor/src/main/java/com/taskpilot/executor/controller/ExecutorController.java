package com.taskpilot.executor.controller;

import com.taskpilot.common.api.ApiResponse;
import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.executor.handler.HandlerRegistry;
import com.taskpilot.executor.service.TaskExecuteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 执行器内部接口，供调度中心调用。返回裸 ExecuteResult，便于调度中心直接映射状态。
 */
@RestController
@RequestMapping("/api/v1/executor")
public class ExecutorController {

    private final TaskExecuteService taskExecuteService;
    private final HandlerRegistry handlerRegistry;

    public ExecutorController(TaskExecuteService taskExecuteService, HandlerRegistry handlerRegistry) {
        this.taskExecuteService = taskExecuteService;
        this.handlerRegistry = handlerRegistry;
    }

    @PostMapping("/execute")
    public ExecuteResult execute(@RequestBody ExecuteRequest request) {
        return taskExecuteService.execute(request);
    }

    @GetMapping("/handlers")
    public ApiResponse<List<String>> handlers() {
        return ApiResponse.ok(List.copyOf(handlerRegistry.names()));
    }
}
