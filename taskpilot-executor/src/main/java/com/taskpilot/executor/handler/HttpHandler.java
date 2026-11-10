package com.taskpilot.executor.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskpilot.common.dto.ExecuteRequest;
import com.taskpilot.common.util.JsonUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP 处理器：GET 一个外部 URL。参数 {"url":"https://example.com"} 或纯 URL。
 */
@Component
public class HttpHandler implements TaskHandler {

    private static final int MAX_BODY_LENGTH = 200;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Override
    public String name() {
        return "httpHandler";
    }

    @Override
    public String execute(ExecuteRequest request) throws Exception {
        String url = extractUrl(request.handlerParam());
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("httpHandler 仅支持 http/https URL");
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body().strip();
        if (body.length() > MAX_BODY_LENGTH) {
            body = body.substring(0, MAX_BODY_LENGTH) + "...";
        }
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("httpHandler 请求失败: HTTP " + response.statusCode() + " " + body);
        }
        return "httpHandler GET " + response.statusCode() + ": " + body;
    }

    private String extractUrl(String param) {
        if (!StringUtils.hasText(param)) {
            throw new IllegalArgumentException("httpHandler 参数不能为空，示例: {\"url\":\"https://example.com\"}");
        }
        String trimmed = param.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        try {
            JsonNode node = JsonUtils.readTree(trimmed);
            if (node.hasNonNull("url")) {
                return node.get("url").asText();
            }
        } catch (IllegalArgumentException ignored) {
            // 统一在下面抛出带示例的错误。
        }
        throw new IllegalArgumentException("httpHandler 参数不合法，示例: {\"url\":\"https://example.com\"}");
    }
}
