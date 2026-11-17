package com.taskpilot.executor.filter;

import com.taskpilot.common.api.ApiResponse;
import com.taskpilot.common.auth.AuthProperties;
import com.taskpilot.common.auth.AuthToken;
import com.taskpilot.common.exception.ErrorCode;
import com.taskpilot.common.util.JsonUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 执行器侧的内部接口鉴权。
 *
 * <p>保护 {@code /api/v1/executor/**}（执行与处理器列表）：这是调度中心调进来的，
 * 执行器得确认"对面确实是拿着 token 的调度中心"，否则任何人都能让执行器跑任意处理器。</p>
 *
 * <p>{@code /actuator/health} 刻意放行：健康检查要给容器编排 / 监控看，
 * 不应该被 token 卡住。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenFilter.class);

    private static final String PROTECTED_PREFIX = "/api/v1/executor/";

    private final String token;

    public AuthTokenFilter(AuthProperties properties) {
        this.token = properties.getToken();
        if (token == null || token.isEmpty()) {
            log.error("taskpilot.auth.token 未配置：执行接口将一律返回 401，" +
                    "请设置环境变量 TASKPILOT_AUTH_TOKEN");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!requiresToken(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(AuthToken.HEADER);
        if (!AuthToken.matches(token, provided)) {
            log.warn("request rejected, invalid token: method={}, path={}, remote={}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            writeUnauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean requiresToken(HttpServletRequest request) {
        return pathWithinApplication(request).startsWith(PROTECTED_PREFIX);
    }

    private String pathWithinApplication(HttpServletRequest request) {
        // B-10：必须按【解码后】路径匹配。getRequestURI() 返回原始 URI，
        // /api/v1/%65xecutor/execute 这类编码前缀会让 startsWith 漏判放行，
        // 而 Spring 的 PathPattern 按解码后路径照样映射到受保护处理器。
        // getServletPath() 由容器解码且已去掉 contextPath（REQUEST dispatch 下成立；
        // 本 filter 只处理 REQUEST）。
        String path = request.getServletPath();
        return path == null ? "" : path;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JsonUtils.toJson(ApiResponse.error(ErrorCode.UNAUTHORIZED)));
    }
}
