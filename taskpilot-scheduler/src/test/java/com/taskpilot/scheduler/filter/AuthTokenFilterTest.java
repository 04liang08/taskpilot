package com.taskpilot.scheduler.filter;

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
 * 调度中心侧鉴权过滤器。
 * <p>只保护执行器注册与心跳；管理接口、静态页、actuator 必须保持开放。</p>
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
    void registerWithCorrectTokenPasses() throws Exception {
        MockHttpServletRequest request = post("/api/v1/executors/register");
        request.addHeader(AuthToken.HEADER, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void heartbeatWithCorrectTokenPasses() throws Exception {
        MockHttpServletRequest request = post("/api/v1/executors/heartbeat");
        request.addHeader(AuthToken.HEADER, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
    }

    @Test
    void registerWithoutTokenIsRejected() throws Exception {
        MockHttpServletRequest request = post("/api/v1/executors/register");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(String.valueOf(ErrorCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void registerWithWrongTokenIsRejected() throws Exception {
        MockHttpServletRequest request = post("/api/v1/executors/register");
        request.addHeader(AuthToken.HEADER, "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");
    }

    @Test
    void managementQueryStaysOpen() throws Exception {
        // GET /api/v1/executors 是管理查询，不该被 token 卡住。
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/executors");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void managementWriteStaysOpen() throws Exception {
        // 任务 CRUD 走管理 API，保持开放（ADR-020：只保护内部接口）。
        MockHttpServletRequest request = post("/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
    }

    @Test
    void actuatorStaysOpen() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
    }

    @Test
    void blankConfiguredTokenRejectsEverything() throws Exception {
        // 没配 token 就等于没开鉴权：宁可一律拒绝，也不能把内部接口裸奔出去。
        AuthProperties properties = new AuthProperties();
        AuthTokenFilter blankFilter = new AuthTokenFilter(properties);
        MockHttpServletRequest request = post("/api/v1/executors/register");
        request.addHeader(AuthToken.HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        blankFilter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void percentEncodedRegisterStillRequiresToken() throws Exception {
        // B-10：原始 URI 带百分号编码（%72=r）时，容器给出的 servletPath 已解码，
        // filter 必须照样拦截——旧实现按 getRequestURI() 匹配会放行。
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/executors/%72egister");
        request.setServletPath("/api/v1/executors/register");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        // 真实容器在 REQUEST dispatch 下把解码后的路径放进 servletPath，filter 以它为准（B-10）。
        request.setServletPath(path);
        return request;
    }
}
