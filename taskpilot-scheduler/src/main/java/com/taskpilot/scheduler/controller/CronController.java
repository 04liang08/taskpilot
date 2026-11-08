package com.taskpilot.scheduler.controller;

import com.taskpilot.common.api.ApiResponse;
import com.taskpilot.scheduler.dto.CronValidateRequest;
import com.taskpilot.scheduler.dto.CronValidateResult;
import com.taskpilot.scheduler.support.CronService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Cron", description = "cron 表达式校验")
@RestController
@RequestMapping("/api/v1/cron")
public class CronController {

    private final CronService cronService;

    public CronController(CronService cronService) {
        this.cronService = cronService;
    }

    @Operation(summary = "校验 cron 并返回未来 5 次触发时间")
    @PostMapping("/validate")
    public ApiResponse<CronValidateResult> validate(@Valid @RequestBody CronValidateRequest request) {
        return ApiResponse.ok(cronService.validate(request.getExpression().trim()));
    }
}