package com.taskpilot.common.auth;

/**
 * 双向共享 token 的配置，对应 taskpilot.auth。
 *
 * <p>调度中心与执行器读的是同一个配置项（{@code taskpilot.auth.token}），
 * 所以两边天然一致：配错的话内部调用会直接 401，而不是"某一台能连另一台不能"。</p>
 */
public class AuthProperties {

    /**
     * 共享静态 token。生产环境必须覆盖（环境变量 {@code TASKPILOT_AUTH_TOKEN}）。
     * <p>为空表示没有配置 token：内部接口一律拒绝（fail-closed），
     * 不会因为漏配就把内部接口裸奔出去。</p>
     */
    private String token = "";

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
