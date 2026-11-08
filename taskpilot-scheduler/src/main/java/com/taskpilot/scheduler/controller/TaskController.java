package com.taskpilot.scheduler.controller;

import com.taskpilot.common.api.ApiResponse;
import com.taskpilot.common.api.PageResult;
import com.taskpilot.common.enums.TaskStatus;
import com.taskpilot.scheduler.dto.TaskCreateRequest;
import com.taskpilot.scheduler.dto.TaskQuery;
import com.taskpilot.scheduler.dto.TaskUpdateRequest;
import com.taskpilot.scheduler.entity.TaskEntity;
import com.taskpilot.scheduler.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "任务管理", description = "任务 CRUD、启停")
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @Operation(summary = "新建任务")
    @PostMapping
    public ApiResponse<TaskEntity> create(@Valid @RequestBody TaskCreateRequest request) {
        return ApiResponse.ok(taskService.create(request));
    }

    @Operation(summary = "更新任务")
    @PutMapping("/{id}")
    public ApiResponse<TaskEntity> update(@PathVariable Long id,
                                          @Valid @RequestBody TaskUpdateRequest request) {
        return ApiResponse.ok(taskService.update(id, request));
    }

    @Operation(summary = "逻辑删除任务")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "分页查询任务")
    @GetMapping
    public ApiResponse<PageResult<TaskEntity>> page(@Valid @ModelAttribute TaskQuery query) {
        return ApiResponse.ok(taskService.page(query));
    }

    @Operation(summary = "任务详情")
    @GetMapping("/{id}")
    public ApiResponse<TaskEntity> detail(@PathVariable Long id) {
        return ApiResponse.ok(taskService.get(id));
    }

    @Operation(summary = "启用任务")
    @PostMapping("/{id}/enable")
    public ApiResponse<TaskEntity> enable(@PathVariable Long id) {
        return ApiResponse.ok(taskService.changeStatus(id, TaskStatus.ENABLED));
    }

    @Operation(summary = "停用任务")
    @PostMapping("/{id}/disable")
    public ApiResponse<TaskEntity> disable(@PathVariable Long id) {
        return ApiResponse.ok(taskService.changeStatus(id, TaskStatus.DISABLED));
    }
}