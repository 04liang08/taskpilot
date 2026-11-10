package com.taskpilot.executor.service;

import com.taskpilot.executor.config.ExecutorProperties;
import com.taskpilot.executor.dto.HeartbeatRequest;
import com.taskpilot.executor.dto.RegisterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 执行器注册与心跳。调度中心不可用时只记录告警，不阻塞启动；
 * 心跳接口本身是 upsert，调度中心恢复后会自动补注册。
 */
@Component
public class ExecutorRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRegistrationService.class);

    private final RestClient schedulerRestClient;
    private final ExecutorProperties properties;

    public ExecutorRegistrationService(RestClient schedulerRestClient, ExecutorProperties properties) {
        this.schedulerRestClient = schedulerRestClient;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerOnStartup() {
        register();
    }

    public void register() {
        RegisterRequest request = new RegisterRequest(
                properties.getAppName(), properties.getGroup(), properties.getAddress());
        try {
            schedulerRestClient.post()
                    .uri("/api/v1/executors/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            log.info("executor registered: app={}, group={}, address={}",
                    properties.getAppName(), properties.getGroup(), properties.getAddress());
        } catch (RestClientException e) {
            log.warn("executor register failed ({}), heartbeat will retry", e.getMessage());
        }
    }

    @Scheduled(initialDelayString = "#{${taskpilot.executor.heartbeat-interval-seconds:10} * 1000}",
            fixedDelayString = "#{${taskpilot.executor.heartbeat-interval-seconds:10} * 1000}")
    public void heartbeat() {
        HeartbeatRequest request = new HeartbeatRequest(properties.getAppName(), properties.getAddress());
        try {
            schedulerRestClient.post()
                    .uri("/api/v1/executors/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("executor heartbeat sent: {}", properties.getAddress());
        } catch (RestClientException e) {
            log.warn("executor heartbeat failed ({}), will retry", e.getMessage());
        }
    }
}
