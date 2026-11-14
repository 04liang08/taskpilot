package com.taskpilot.executor.service;

import com.taskpilot.executor.config.ExecutorProperties;
import com.taskpilot.executor.config.SchedulerClientFactory;
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

import java.util.List;

/**
 * 执行器注册与心跳。调度中心不可用时只记录告警，不阻塞启动；
 * 心跳接口本身是 upsert，调度中心恢复后会自动补注册。
 * <p>支持配置多个调度中心地址：逐个尝试，只要有一个通就算成功——
 * 注册表在共享 DB 里，任意一个调度中心收到心跳就能维持 ONLINE 状态。</p>
 */
@Component
public class ExecutorRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRegistrationService.class);

    private final List<RestClient> schedulerClients;
    private final ExecutorProperties properties;

    public ExecutorRegistrationService(SchedulerClientFactory clientFactory, ExecutorProperties properties) {
        this.schedulerClients = clientFactory.clients();
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerOnStartup() {
        register();
    }

    public void register() {
        RegisterRequest request = new RegisterRequest(
                properties.getAppName(), properties.getGroup(), properties.getAddress());
        if (sendToSchedulers(request, "/api/v1/executors/register")) {
            log.info("executor registered: app={}, group={}, address={}",
                    properties.getAppName(), properties.getGroup(), properties.getAddress());
        } else {
            log.warn("executor register failed on all {} scheduler(s), heartbeat will retry",
                    schedulerClients.size());
        }
    }

    @Scheduled(initialDelayString = "#{${taskpilot.executor.heartbeat-interval-seconds:10} * 1000}",
            fixedDelayString = "#{${taskpilot.executor.heartbeat-interval-seconds:10} * 1000}")
    public void heartbeat() {
        HeartbeatRequest request = new HeartbeatRequest(properties.getAppName(), properties.getAddress());
        if (!sendToSchedulers(request, "/api/v1/executors/heartbeat")) {
            log.warn("executor heartbeat failed on all {} scheduler(s), will retry", schedulerClients.size());
        }
    }

    /**
     * 逐个尝试所有调度中心地址。任意一个成功即视为成功；全部失败才告警。
     */
    private boolean sendToSchedulers(Object body, String path) {
        if (schedulerClients.isEmpty()) {
            log.error("no scheduler address configured, cannot send {}", path);
            return false;
        }
        boolean anySuccess = false;
        for (RestClient client : schedulerClients) {
            try {
                client.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                anySuccess = true;
            } catch (RestClientException e) {
                log.debug("scheduler {} unreachable for {}: {}", client, path, e.getMessage());
            }
        }
        return anySuccess;
    }
}
