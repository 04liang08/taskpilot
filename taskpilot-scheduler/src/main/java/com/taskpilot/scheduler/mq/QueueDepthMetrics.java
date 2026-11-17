package com.taskpilot.scheduler.mq;

import com.taskpilot.common.metrics.MetricNames;
import com.taskpilot.common.mq.MqTopology;
import com.taskpilot.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 队列深度 gauge（ADR-022）：spring-rabbit 的 micrometer 绑定只到连接/信道与收发速率，
 * <b>不含单队列深度</b>——这里用 RabbitMQ management API 轮询补齐
 * {@code taskpilot.mq.queue.depth{queue=...}}。
 *
 * <p>优雅降级是硬要求：broker / management 插件不可达时保留上次读数（初始 -1 表示"还没取到过"），
 * 只打 warn，轮询异常绝不能外溢拖垮 scheduler 的 @Scheduled 线程池。</p>
 */
@Component
public class QueueDepthMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthMetrics.class);

    /** vhost 固定 "/"（management API 路径里 URL 编码为 %2F），与部署与冒烟脚本一致。 */
    private static final String VHOST = "%2F";

    private static final List<String> QUEUES = List.of(MqTopology.RESULTS_QUEUE, MqTopology.DEAD_QUEUE);

    private final RestClient restClient;
    private final ConcurrentMap<String, AtomicLong> depths = new ConcurrentHashMap<>();

    public QueueDepthMetrics(MeterRegistry meterRegistry,
                             @Value("${taskpilot.metrics.rabbit-management.url:http://127.0.0.1:15672}") String url,
                             @Value("${spring.rabbitmq.username:taskpilot}") String username,
                             @Value("${spring.rabbitmq.password:taskpilot}") String password) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // 短超时：拿不到就下一轮再说，不占调度线程。
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBasicAuth(username, password))
                .build();
        for (String queue : QUEUES) {
            AtomicLong depth = new AtomicLong(-1L);
            depths.put(queue, depth);
            Gauge.builder(MetricNames.QUEUE_DEPTH, depth, AtomicLong::doubleValue)
                    .description("队列消息堆积数（management API 轮询，-1=尚未取到）")
                    .tag("queue", queue)
                    .register(meterRegistry);
        }
    }

    /** 15s 轮询一次：与抓取/演示刷新节奏匹配，对 management API 的负载可以忽略。 */
    @Scheduled(initialDelayString = "5000", fixedDelayString = "15000")
    public void poll() {
        for (String queue : QUEUES) {
            Long depth = fetchDepth(queue);
            if (depth != null) {
                depths.get(queue).set(depth);
            }
            // null = 本轮没取到：保留上次值（降级），fetchDepth 里已打过 warn。
        }
    }

    /** 取一个队列的 messages 总数（ready+unacknowledged）；失败返回 null 触发降级。 */
    Long fetchDepth(String queue) {
        try {
            String body = restClient.get()
                    .uri("/api/queues/" + VHOST + "/" + queue)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return null;
            }
            JsonNode node = JsonUtils.readTree(body).path("messages");
            return node.isNumber() ? node.asLong() : null;
        } catch (Exception e) {
            log.warn("rabbit management queue depth poll failed, keep last value: queue={}, reason={}",
                    queue, e.getMessage());
            return null;
        }
    }
}
