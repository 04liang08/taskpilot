package com.taskpilot.scheduler.controller;

import com.taskpilot.common.api.ApiResponse;
import com.taskpilot.common.api.PageResult;
import com.taskpilot.scheduler.dto.ExecutorHeartbeatRequest;
import com.taskpilot.scheduler.dto.ExecutorQuery;
import com.taskpilot.scheduler.dto.ExecutorRegisterRequest;
import com.taskpilot.scheduler.entity.ExecutorEntity;
import com.taskpilot.scheduler.service.ExecutorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "执行器管理", description = "注册、心跳、在线状态查询")
@RestController
@RequestMapping("/api/v1/executors")
public class ExecutorController {

    private final ExecutorService executorService;

    public ExecutorController(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Operation(summary = "执行器注册")
    @PostMapping("/register")
    public ApiResponse<ExecutorEntity> register(@Valid @RequestBody ExecutorRegisterRequest request) {
        return ApiResponse.ok(executorService.register(request));
    }

    @Operation(summary = "执行器心跳")
    @PostMapping("/heartbeat")
    public ApiResponse<ExecutorEntity> heartbeat(@Valid @RequestBody ExecutorHeartbeatRequest request) {
        return ApiResponse.ok(executorService.heartbeat(request));
    }

    @Operation(summary = "分页查询执行器")
    @GetMapping
    public ApiResponse<PageResult<ExecutorEntity>> page(@Valid @ModelAttribute ExecutorQuery query) {
        return ApiResponse.ok(executorService.page(query));
    }

    @Operation(summary = "执行器详情")
    @GetMapping("/{id}")
    public ApiResponse<ExecutorEntity> detail(@PathVariable Long id) {
        return ApiResponse.ok(executorService.get(id));
    }
}
