package com.taskpilot.scheduler.filter;

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
 * 调度中心侧的内部接口鉴权。
 *
 * <p><b>只保护</b>执行器注册与心跳两个接口：</p>
 * <ul>
 *   <li>{@code POST /api/v1/executors/register}</li>
 *   <li>{@code POST /api/v1/executors/heartbeat}</li>
 * </ul>
 *
 * <p>这两个是执行器调进来的，调度中心得确认"对面确实是拿着 token 的执行器"，
 * 否则任何人都能把执行器顶下线（伪造下线事件 = 误触发失败转移）。</p>
 *
 * <p><b>刻意不保护</b>管理接口（任务 CRUD / 执行记录查询 / 仪表盘）、静态演示页与 actuator：
 * 那些是给人用的，只跑在内网 demo 环境（见 ADR-020）。全部加上鉴权会让
 * "打开页面就能看"的演示体验变成"先登录"，收益不大、代价不小。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenFilter.class);

    private static final String REGISTER_PATH = "/api/v1/executors/register";
    private static final String HEARTBEAT_PATH = "/api/v1/executors/heartbeat";

    private final String token;

    public AuthTokenFilter(AuthProperties properties) {
        this.token = properties.getToken();
        if (token == null || token.isEmpty()) {
            log.error("taskpilot.auth.token 未配置：执行器注册与心跳接口将一律返回 401，" +
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

    /**
     * 是否需要 token：只看执行器注册与心跳，且必须是 POST。
     * <p>按方法区分是有意的：{@code GET /api/v1/executors} 是管理查询，保持开放。</p>
     */
    private boolean requiresToken(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = pathWithinApplication(request);
        return REGISTER_PATH.equals(path) || HEARTBEAT_PATH.equals(path);
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JsonUtils.toJson(ApiResponse.error(ErrorCode.UNAUTHORIZED)));
    }
}
