package com.taskpilot.executor.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.util.JsonUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 演示处理器：支持 {"message":"hello"}，也兼容纯字符串参数。
 */
@Component
public class DemoHandler implements TaskHandler {

    @Override
    public String name() {
        return "demoHandler";
    }

    @Override
    public String execute(ExecuteRequest request) {
        return "demoHandler done: " + extractMessage(request.handlerParam());
    }

    private String extractMessage(String param) {
        if (!StringUtils.hasText(param)) {
            return "hello";
        }
        try {
            JsonNode node = JsonUtils.readTree(param);
            if (node.hasNonNull("message")) {
                return node.get("message").asText();
            }
            return param;
        } catch (IllegalArgumentException e) {
            // 非 JSON 参数按普通字符串处理，方便 curl 直接演示。
            return param;
        }
    }
}
