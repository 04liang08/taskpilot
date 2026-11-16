package com.taskpilot.scheduler.client;

import com.taskpilot.common.auth.AuthProperties;
import com.taskpilot.common.auth.AuthToken;
import com.taskpilot.common.dto.ExecuteAck;
import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.scheduler.config.ExecutorDispatchProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度中心调执行器时必须带共享 token（ADR-020）。
 * <p>用 JDK 自带的 HttpServer 起一个真服务来断言请求头：RestClient 真实发出的请求才算数。</p>
 */
class ExecutorClientTest {

    private HttpServer server;
    private final AtomicReference<String> receivedToken = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/executor/execute", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst(AuthToken.HEADER));
            byte[] body = "{\"accepted\":true,\"rejectReason\":null}".getBytes(StandardCharsets.UTF_8);
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
    void sendsTheSharedTokenHeader() {
        AuthProperties authProperties = new AuthProperties();
        authProperties.setToken("token-123");
        ExecutorClient client = new ExecutorClient(new ExecutorDispatchProperties(), authProperties);

        ExecuteAck ack = client.execute(address(), request());

        assertThat(ack.accepted()).isTrue();
        assertThat(receivedToken.get()).isEqualTo("token-123");
    }

    private String address() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    private ExecuteRequest request() {
        return new ExecuteRequest(100L, 1L, "demoHandler", "{}", 0, 1, 30);
    }
}
