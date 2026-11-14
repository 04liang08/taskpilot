package com.taskpilot.executor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 调度中心客户端工厂：按配置的多个地址各建一个 RestClient。
 * <p>支持多个地址是为了解决"执行器只注册一个调度中心"的单点问题：
 * 任意一个调度中心挂掉时，执行器仍能通过另一个地址完成注册与心跳，
 * 不会因为心跳断掉被误判成离线。</p>
 */
@Component
public class SchedulerClientFactory {

    private static final Logger log = LoggerFactory.getLogger(SchedulerClientFactory.class);

    private final List<String> addresses;
    private final List<RestClient> clients;

    public SchedulerClientFactory(ExecutorProperties properties) {
        List<String> resolved = new ArrayList<>();
        for (String baseUrl : properties.getSchedulerBaseUrl().split(",")) {
            String trimmed = baseUrl.trim();
            if (StringUtils.hasText(trimmed)) {
                resolved.add(trimmed);
            }
        }
        this.addresses = List.copyOf(resolved);

        List<RestClient> list = new ArrayList<>();
        for (String address : addresses) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
            requestFactory.setReadTimeout(properties.getReadTimeoutMs());
            list.add(RestClient.builder()
                    .baseUrl(address)
                    .requestFactory(requestFactory)
                    .build());
        }
        this.clients = List.copyOf(list);
        log.info("scheduler addresses: {}", addresses);
    }

    public List<RestClient> clients() {
        return clients;
    }

    public List<String> addresses() {
        return addresses;
    }
}
