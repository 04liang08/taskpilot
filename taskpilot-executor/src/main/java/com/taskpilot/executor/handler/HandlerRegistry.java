package com.taskpilot.executor.handler;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 处理器注册表：启动时收集所有 TaskHandler Bean，按名称索引。
 * 重名或空名称直接启动失败，避免运行期才发现配置错误。
 */
@Component
public class HandlerRegistry {

    private final Map<String, TaskHandler> handlers;

    public HandlerRegistry(List<TaskHandler> handlerBeans) {
        Map<String, TaskHandler> map = new LinkedHashMap<>();
        for (TaskHandler handler : handlerBeans) {
            String name = handler.name();
            if (!StringUtils.hasText(name)) {
                throw new IllegalStateException("处理器名称不能为空: " + handler.getClass().getName());
            }
            TaskHandler previous = map.putIfAbsent(name, handler);
            if (previous != null) {
                throw new IllegalStateException("处理器名称重复: " + name
                        + "（" + previous.getClass().getName() + " / " + handler.getClass().getName() + "）");
            }
        }
        this.handlers = Collections.unmodifiableMap(map);
    }

    public TaskHandler require(String name) {
        TaskHandler handler = handlers.get(name);
        if (handler == null) {
            throw new IllegalArgumentException("未注册的处理器: " + name + "，可用处理器: " + handlers.keySet());
        }
        return handler;
    }

    public Set<String> names() {
        return handlers.keySet();
    }
}
