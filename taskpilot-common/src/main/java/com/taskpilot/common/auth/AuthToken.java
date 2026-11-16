package com.taskpilot.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 双向共享静态 token 的公共约定。
 *
 * <p>第 4 周起，调度中心与执行器之间的内部接口需要互相证明身份：</p>
 * <ul>
 *   <li>调度中心 → 执行器：{@code POST /api/v1/executor/**}；</li>
 *   <li>执行器 → 调度中心：{@code POST /api/v1/executors/register}、{@code POST /api/v1/executors/heartbeat}。</li>
 * </ul>
 * <p>请求头统一是 {@code X-TaskPilot-Token}。</p>
 */
public final class AuthToken {

    /** 携带 token 的请求头。 */
    public static final String HEADER = "X-TaskPilot-Token";

    private AuthToken() {
    }

    /**
     * 常量时间比对 token。
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：
     * 后者会按字符逐位比较并在第一个不匹配处返回，耗时差能被用来一位一位地
     * 猜 token（时序侧信道）。前者无论是否匹配，耗时基本一致。</p>
     *
     * @param expected 配置的 token（为空表示没配，一律拒绝）
     * @param provided 请求头里带来的 token
     */
    public static boolean matches(String expected, String provided) {
        if (expected == null || expected.isEmpty()) {
            // 没配 token 就等于没开鉴权：直接放行等于把内部接口裸奔。
            return false;
        }
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
