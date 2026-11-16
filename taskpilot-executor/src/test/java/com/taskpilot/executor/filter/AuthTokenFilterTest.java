package com.taskpilot.executor.filter;

import com.taskpilot.common.auth.AuthProperties;
import com.taskpilot.common.auth.AuthToken;
import com.taskpilot.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行器侧鉴权过滤器。
 * <p>保护 {@code /api/v1/executor/**}；健康检查与其它路径保持开放。</p>
 */
class AuthTokenFilterTest {

    private static final String TOKEN = "unit-test-token";

    private AuthTokenFilter filter;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setToken(TOKEN);
        filter = new AuthTokenFilter(properties);
    }

    @Test
    void executeWithCorrectTokenPasses() throws Exception {
        MockHttpServletRequest request = post("/api/v1/executor/execute");
        request.addHeader(AuthToken.HEADER, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void executeWithoutTokenIsRejected() throws Exception {
        MockHttpServletRequest request = post("/api/v1/executor/execute");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(String.valueOf(ErrorCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void executeWithWrongTokenIsRejected() throws Exception {
        MockHttpServletRequest request = post("/api/v1/executor/execute");
        request.addHeader(AuthToken.HEADER, "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void handlersEndpointIsAlsoProtected() throws Exception {
        // 处理器列表也是内部接口：不能让外人看到有哪些 handler 能被调用。
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/executor/handlers");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void healthEndpointStaysOpen() throws Exception {
        // 健康检查要给容器编排 / 监控看，不能被 token 卡住。
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest post(String path) {
        return new MockHttpServletRequest("POST", path);
    }
}
