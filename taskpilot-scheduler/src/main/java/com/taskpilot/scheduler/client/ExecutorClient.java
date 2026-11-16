package com.taskpilot.scheduler.client;

import com.taskpilot.common.dto.ExecuteAck;
import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.scheduler.config.ExecutorDispatchProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * 按注册地址直连执行器。
 * <p>第 4 周起这里只做"派发"：执行接口立即回 ACK（受理与否），
 * 真正的执行结果由执行器经 RabbitMQ 异步回传。
 * 所以读超时按"ACK 是快操作"来配（默认 5 秒），不再跟着任务超时时间走。</p>
 */
public class ExecutorClient {

    private final ExecutorDispatchProperties properties;

    public ExecutorClient(ExecutorDispatchProperties properties) {
        this.properties = properties;
    }

    /**
     * 派发一个执行请求。
     *
     * @return 执行器的受理回执
     */
    public ExecuteAck execute(String address, ExecuteRequest request) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());

        RestClient client = RestClient.builder()
                .requestFactory(requestFactory)
                .build();

        ExecuteAck ack = client.post()
                .uri(URI.create("http://" + address + "/api/v1/executor/execute"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ExecuteAck.class);
        if (ack == null) {
            throw new IllegalStateException("执行器返回空回执");
        }
        return ack;
    }
}
