package com.taskpilot.scheduler.client;

import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.dto.ExecuteResult;
import com.taskpilot.scheduler.config.ExecutorDispatchProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * 按注册地址直连执行器。
 * <p>每次调用单独构造 RestClient，读超时按“任务超时 + 5 秒”计算，避免长任务被默认超时误杀。</p>
 */
public class ExecutorClient {

    private static final int TIMEOUT_MARGIN_SECONDS = 5;

    private final ExecutorDispatchProperties properties;

    public ExecutorClient(ExecutorDispatchProperties properties) {
        this.properties = properties;
    }

    public ExecuteResult execute(String address, ExecuteRequest request) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(resolveReadTimeoutMs(request.timeoutSeconds()));

        RestClient client = RestClient.builder()
                .requestFactory(requestFactory)
                .build();

        ExecuteResult result = client.post()
                .uri(URI.create("http://" + address + "/api/v1/executor/execute"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ExecuteResult.class);
        if (result == null) {
            throw new IllegalStateException("执行器返回空结果");
        }
        return result;
    }

    private int resolveReadTimeoutMs(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return properties.getReadTimeoutMs();
        }
        long timeoutMs = (timeoutSeconds + (long) TIMEOUT_MARGIN_SECONDS) * 1000L;
        return (int) Math.min(timeoutMs, Integer.MAX_VALUE);
    }
}
