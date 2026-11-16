package com.taskpilot.scheduler.mq;

import com.taskpilot.common.dto.AlertMessage;
import com.taskpilot.common.mq.MqTopology;
import com.taskpilot.scheduler.config.AlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 告警消费者：把告警推到外部 webhook。
 *
 * <p>webhook 地址是可选项（{@code taskpilot.alert.webhook-url}）：
 * 没配就只记日志，不做邮件 / 短信这类"再引入一个中间件"的事。</p>
 * <p>推送失败只记日志，不重投：告警本身已经落在死信表里了，
 * 一直重投会把这个消费者堵死。</p>
 */
@Component
public class AlertWebhookListener {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookListener.class);

    private final AlertProperties properties;
    private final RestClient restClient;

    public AlertWebhookListener(AlertProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @RabbitListener(queues = MqTopology.ALERT_QUEUE)
    public void onAlert(AlertMessage message) {
        String url = properties.getWebhookUrl();
        if (!StringUtils.hasText(url)) {
            log.warn("alert raised but webhook url is not configured: type={}, executionId={}, taskId={}, message={}",
                    message.alertType(), message.executionId(), message.taskId(), message.message());
            return;
        }
        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .toBodilessEntity();
            log.info("alert webhook delivered: type={}, executionId={}, taskId={}",
                    message.alertType(), message.executionId(), message.taskId());
        } catch (Exception e) {
            log.error("alert webhook delivery failed: type={}, executionId={}, reason={}",
                    message.alertType(), message.executionId(), e.getMessage());
        }
    }
}
