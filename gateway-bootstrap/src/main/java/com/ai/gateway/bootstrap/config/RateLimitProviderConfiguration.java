package com.ai.gateway.bootstrap.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 限流装配（可插拔，里程碑 M3）。
 *
 * <p>聚合限流端口的两套实现：</p>
 * <ul>
 * <li>{@link StubRateLimitConfiguration}：默认激活（{@code gateway.ratelimit.provider}
 * 未设置或为 {@code stub}），始终放行；</li>
 * <li>{@link SentinelRateLimitConfiguration}：在
 * {@code gateway.ratelimit.provider=sentinel} 时激活，加载 Sentinel
 * 流控/熔断规则。</li>
 * </ul>
 *
 * @since 0.1.0
 */
@Configuration
@Import({
        StubRateLimitConfiguration.class,
        SentinelRateLimitConfiguration.class,
})
public class RateLimitProviderConfiguration {
}
