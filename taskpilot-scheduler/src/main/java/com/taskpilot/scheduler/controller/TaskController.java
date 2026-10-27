package com.taskpilot.scheduler.controller;

import com.taskpilot.common.api.ApiResponse;
import com.taskpilot.common.api.PageResult;
import com.taskpilot.scheduler.dto.TaskCreateRequest;
import com.taskpilot.scheduler.dto.TaskQuery;
import com.taskpilot.scheduler.dto.TaskResponse;
import com.taskpilot.scheduler.dto.TaskUpdateRequest;
import com.taskpilot.scheduler.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "任务管理", description = "任务 CRUD 与启停")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @Operation(summary = "新建任务")
    public ApiResponse<TaskResponse> create(@Valid @RequestBody TaskCreateRequest request) {
        return ApiResponse.ok(taskService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新任务")
    public ApiResponse<TaskResponse> update(@PathVariable Long id,
                                            @Valid @RequestBody TaskUpdateRequest request) {
        return ApiResponse.ok(taskService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "逻辑删除任务")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping
    @Operation(summary = "分页查询任务")
    public ApiResponse<PageResult<TaskResponse>> page(@Valid TaskQuery query) {
        return ApiResponse.ok(taskService.page(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询任务详情")
    public ApiResponse<TaskResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(taskService.get(id));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "启用任务")
    public ApiResponse<TaskResponse> enable(@PathVariable Long id) {
        return ApiResponse.ok(taskService.enable(id));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "停用任务")
    public ApiResponse<TaskResponse> disable(@PathVariable Long id) {
        return ApiResponse.ok(taskService.disable(id));
    }
}
