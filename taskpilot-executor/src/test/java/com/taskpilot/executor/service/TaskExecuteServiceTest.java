package com.taskpilot.executor.service;

import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.executor.handler.DemoHandler;
import com.taskpilot.executor.handler.HandlerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecuteServiceTest {

    private ThreadPoolTaskExecutor executor;
    private TaskExecuteService service;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.initialize();
        service = new TaskExecuteService(new HandlerRegistry(List.of(new DemoHandler())), executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void executesRegisteredHandlerAndReturnsSummary() {
        ExecuteRequest request = new ExecuteRequest(10L, 1L, "demoHandler",
                "{\"message\":\"hi\"}", 0, 1, 5);

        ExecuteResult result = service.execute(request);

        assertThat(result.success()).isTrue();
        assertThat(result.timeout()).isFalse();
        assertThat(result.resultSummary()).isEqualTo("demoHandler done: hi");
    }

    @Test
    void unknownHandlerReturnsFailure() {
        ExecuteRequest request = new ExecuteRequest(11L, 1L, "missingHandler",
                null, 0, 1, 5);

        ExecuteResult result = service.execute(request);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("未注册的处理器");
    }
}
