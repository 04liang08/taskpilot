package com.taskpilot.scheduler.mq;

import com.sun.net.httpserver.HttpServer;
import com.taskpilot.common.metrics.MetricNames;
import com.taskpilot.common.mq.MqTopology;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 队列深度 gauge（Week 4 收尾②）：从 management API 轮询解析 messages，
 * broker 不可达时保留读数（降级），注册出 taskpilot_mq_queue_depth{queue=...}。
 * <p>用 JDK HttpServer 喂 management JSON 样例，断言真实 HTTP 行为（风格对齐 ExecutorClientTest）。</p>
 */
class QueueDepthMetricsTest {

    private static HttpServer server;
    private static String baseUrl;

    private SimpleMeterRegistry registry;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 注册根路径：JDK HttpServer 按解码路径匹配 context（raw %2F 会被解码成 //），
        // 本测试的职责是"解析 messages / 降级 / gauge 注册"，线格式（原始 %2F）
        // 由 queueUriKeepsPercentEncodedVhostRaw 专门钉住，真实语义在生产验证过。
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains(MqTopology.RESULTS_QUEUE)) {
                respond(exchange, 200, "{\"name\":\"" + MqTopology.RESULTS_QUEUE + "\",\"messages\":42}");
            } else if (path.contains(MqTopology.DEAD_QUEUE)) {
                respond(exchange, 200, "{\"name\":\"" + MqTopology.DEAD_QUEUE + "\",\"messages\":0}");
            } else {
                respond(exchange, 404, "{\"error\":\"Object Not Found\"}");
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @BeforeEach
    void setUp() {
        // 每个用例独立 registry：同名同标签的 gauge 在 micrometer 里会复用第一次注册的对象。
        registry = new SimpleMeterRegistry();
    }

    @Test
    void pollsDepthsFromManagementApiAndRegistersGauges() {
        QueueDepthMetrics metrics = new QueueDepthMetrics(registry, baseUrl, "taskpilot", "taskpilot");

        // 构造即注册两条 gauge，poll 之前保持初始值 -1（"还没取到过"）。
        assertThat(registry.getMeters()).filteredOn(m -> m.getId().getName().equals(MetricNames.QUEUE_DEPTH))
                .hasSize(2);
        assertThat(gauge(MqTopology.RESULTS_QUEUE).value()).isEqualTo(-1.0);

        metrics.poll();

        assertThat(gauge(MqTopology.RESULTS_QUEUE).value()).isEqualTo(42.0);
        assertThat(gauge(MqTopology.DEAD_QUEUE).value()).isZero();
    }

    @Test
    void unreachableManagementKeepsValueAndNeverThrows() {
        // 端口 1 必连不上：poll 不抛异常（不拖垮 @Scheduled 线程），读数维持初始 -1。
        QueueDepthMetrics dead = new QueueDepthMetrics(registry, "http://127.0.0.1:1", "taskpilot", "taskpilot");

        assertThatCode(() -> dead.poll()).doesNotThrowAnyException();
        assertThat(gauge(MqTopology.RESULTS_QUEUE).value()).isEqualTo(-1.0);
    }

    @Test
    void missingMessagesFieldDegradesToNull() {
        QueueDepthMetrics metrics = new QueueDepthMetrics(registry, baseUrl, "taskpilot", "taskpilot");

        // 404 / 非预期 JSON 一律解析成 null → poll 里走"保留上次值"分支。
        assertThat(metrics.fetchDepth("no.such.queue")).isNull();
    }

    @Test
    void queueUriKeepsPercentEncodedVhostRaw() {
        // 回归防护在客户端侧：management API 的 vhost 必须以原始 %2F 发送，
        // 一旦被 uriTemplate 重新编解码成 /，RabbitMQ 会 404（真实集成里踩过）。
        URI uri = URI.create(baseUrl + QueueDepthMetrics.queueApiPath(MqTopology.RESULTS_QUEUE));

        assertThat(uri.getRawPath()).contains("/api/queues/%2F/");
        assertThat(uri.getRawPath()).doesNotContain("//taskpilot");
    }

    private Gauge gauge(String queue) {
        return registry.get(MetricNames.QUEUE_DEPTH).tag("queue", queue).gauge();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
