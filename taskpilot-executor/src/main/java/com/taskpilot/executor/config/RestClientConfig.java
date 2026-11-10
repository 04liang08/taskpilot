package com.taskpilot.executor.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 执行器调用调度中心的 RestClient。注册/心跳都是短请求，超时设短一些，避免线程被拖住。
 */
@Configuration
@EnableConfigurationProperties(ExecutorProperties.class)
public class RestClientConfig {

    @Bean
    public RestClient schedulerRestClient(ExecutorProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        return RestClient.builder()
                .baseUrl(properties.getSchedulerBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
