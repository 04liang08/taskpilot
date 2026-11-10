package com.taskpilot.executor.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.util.JsonUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 睡眠处理器：用于演示耗时、超时与线程池隔离。参数 {"millis":1000} 或纯数字。
 */
@Component
public class SleepHandler implements TaskHandler {

    private static final long DEFAULT_SLEEP_MILLIS = 1000L;
    private static final long MAX_SLEEP_MILLIS = 300_000L;

    @Override
    public String name() {
        return "sleepHandler";
    }

    @Override
    public String execute(ExecuteRequest request) throws InterruptedException {
        long millis = parseMillis(request.handlerParam());
        Thread.sleep(millis);
        return "sleepHandler slept " + millis + "ms";
    }

    private long parseMillis(String param) {
        if (!StringUtils.hasText(param)) {
            return DEFAULT_SLEEP_MILLIS;
        }
        String trimmed = param.trim();
        if (trimmed.matches("\\d+")) {
            return clamp(Long.parseLong(trimmed));
        }
        try {
            JsonNode node = JsonUtils.readTree(trimmed);
            if (node.isNumber()) {
                return clamp(node.asLong());
            }
            if (node.hasNonNull("millis")) {
                return clamp(node.get("millis").asLong());
            }
        } catch (IllegalArgumentException ignored) {
            // 统一在下面抛出带示例的错误。
        }
        throw new IllegalArgumentException("sleepHandler 参数不合法，示例: {\"millis\":1000}");
    }

    private long clamp(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("sleepHandler 的 millis 不能为负数");
        }
        return Math.min(millis, MAX_SLEEP_MILLIS);
    }
}
