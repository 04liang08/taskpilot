package com.taskpilot.executor.handler;

import com.taskpilot.common.dto.ExecuteRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandlerRegistryTest {

    @Test
    void indexesHandlersByName() {
        DemoHandler demoHandler = new DemoHandler();
        SleepHandler sleepHandler = new SleepHandler();

        HandlerRegistry registry = new HandlerRegistry(List.of(demoHandler, sleepHandler));

        assertThat(registry.names()).containsExactly("demoHandler", "sleepHandler");
        assertThat(registry.require("demoHandler")).isSameAs(demoHandler);
    }

    @Test
    void rejectsUnknownHandler() {
        HandlerRegistry registry = new HandlerRegistry(List.of(new DemoHandler()));

        assertThatThrownBy(() -> registry.require("missingHandler"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册的处理器");
    }

    @Test
    void rejectsDuplicateHandlerName() {
        TaskHandler duplicate = new TaskHandler() {
            @Override
            public String name() {
                return "demoHandler";
            }

            @Override
            public String execute(ExecuteRequest request) {
                return "duplicate";
            }
        };

        assertThatThrownBy(() -> new HandlerRegistry(List.of(new DemoHandler(), duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("处理器名称重复");
    }

    @Test
    void demoHandlerReadsMessageFromJsonParam() {
        ExecuteRequest request = new ExecuteRequest(1L, 1L, "demoHandler",
                "{\"message\":\"hello taskpilot\"}", 0, 1, 30);

        String summary = new DemoHandler().execute(request);

        assertThat(summary).isEqualTo("demoHandler done: hello taskpilot");
    }
}
