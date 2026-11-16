package com.taskpilot.executor.config;

import com.taskpilot.common.auth.AuthProperties;
import com.taskpilot.common.auth.AuthToken;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行器注册 / 心跳时必须带共享 token（ADR-020）。
 * <p>用 JDK 自带的 HttpServer 起一个真服务来断言请求头：RestClient 真实发出的请求才算数。</p>
 */
class SchedulerClientFactoryTest {

    private HttpServer server;
    private final AtomicReference<String> receivedToken = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/executors/register", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst(AuthToken.HEADER));
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void registerCallCarriesTheSharedTokenHeader() {
        ExecutorProperties properties = new ExecutorProperties();
        properties.setSchedulerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        AuthProperties authProperties = new AuthProperties();
        authProperties.setToken("token-123");

        SchedulerClientFactory factory = new SchedulerClientFactory(properties, authProperties);
        RestClient client = factory.clients().get(0);
        client.post()
                .uri("/api/v1/executors/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .toBodilessEntity();

        assertThat(receivedToken.get()).isEqualTo("token-123");
    }
}
