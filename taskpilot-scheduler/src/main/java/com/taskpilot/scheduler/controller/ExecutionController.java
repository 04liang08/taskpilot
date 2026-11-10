package com.taskpilot.scheduler.controller;

import com.taskpilot.common.api.ApiResponse;
import com.taskpilot.common.api.PageResult;
import com.taskpilot.scheduler.dto.ExecutionQuery;
import com.taskpilot.scheduler.entity.ExecutionLogEntity;
import com.taskpilot.scheduler.service.ExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "执行记录", description = "手动触发结果、分页与详情")
@RestController
@RequestMapping("/api/v1/executions")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @Operation(summary = "分页查询执行记录")
    @GetMapping
    public ApiResponse<PageResult<ExecutionLogEntity>> page(@Valid @ModelAttribute ExecutionQuery query) {
        return ApiResponse.ok(executionService.page(query));
    }

    @Operation(summary = "执行记录详情")
    @GetMapping("/{id}")
    public ApiResponse<ExecutionLogEntity> detail(@PathVariable Long id) {
        return ApiResponse.ok(executionService.get(id));
    }
}
