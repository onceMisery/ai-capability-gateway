package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.port.RateLimiterPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 初始发布版本的默认（桩）限流装配。
 *
 * <p>当 {@code gateway.ratelimit.provider} 未设置或为 {@code stub} 时生效。
 * 选择 {@code gateway.ratelimit.provider=sentinel} 则会激活
 * {@code SentinelRateLimitConfiguration}。</p>
 *
 * <p>该桩实现始终放行请求——即不执行任何限流——符合初始发布的降级规则。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
@ConditionalOnProperty(name = "gateway.ratelimit.provider", havingValue = "stub", matchIfMissing = true)
public class StubRateLimitConfiguration {

    /**
     * 始终放行请求的桩 {@link RateLimiterPort}。
     *
     * @return 始终放行的限流器
     */
    @Bean
    public RateLimiterPort rateLimiterPort() {
        return (dimension, key, permits) -> true;
    }
}
