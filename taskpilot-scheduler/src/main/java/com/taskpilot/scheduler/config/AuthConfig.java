package com.taskpilot.scheduler.config;

import com.taskpilot.common.auth.AuthProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 鉴权配置注册。
 *
 * <p>token 用 {@code @Value} 直接绑定，不给 {@code @ConfigurationProperties} 注解：
 * {@link AuthProperties} 放在 common 里保持零 Spring 依赖，两边各自绑定同一个
 * {@code taskpilot.auth.token} 配置项即可。</p>
 */
@Configuration
public class AuthConfig {

    @Bean
    public AuthProperties authProperties(@Value("${taskpilot.auth.token:}") String token) {
        AuthProperties properties = new AuthProperties();
        properties.setToken(token);
        return properties;
    }
}
