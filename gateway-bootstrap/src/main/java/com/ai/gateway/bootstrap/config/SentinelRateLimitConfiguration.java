package com.ai.gateway.bootstrap.config;

import com.ai.gateway.bootstrap.ratelimit.SentinelRateLimiterAdapter;
import com.ai.gateway.bootstrap.ratelimit.SentinelRuleInitializer;
import com.ai.gateway.domain.port.RateLimiterPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Sentinel 限流引擎的条件化 Spring 装配。
 *
 * <p>仅当 {@code gateway.ratelimit.provider=sentinel} 时生效。未生效时，
 * 使用始终放行的桩 {@link RateLimiterPort}（由 {@code StubRateLimitConfiguration} 注册）。</p>
 *
 * <p>依据技术选型文档 §10，Sentinel 被合并进 bootstrap 模块（而非独立适配器），
 * 因为它以极小体积实现单一端口，且属于在启动时自然装配的横切关注点。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
@ConditionalOnProperty(name = "gateway.ratelimit.provider", havingValue = "sentinel")
public class SentinelRateLimitConfiguration {

    /**
     * Sentinel 的 {@link RateLimiterPort} 实现。
     *
     * @return Sentinel 限流适配器
     */
    @Bean
    public RateLimiterPort rateLimiterPort() {
        return new SentinelRateLimiterAdapter();
    }

    /**
     * 在启动时加载硬编码的 Sentinel 规则。
     *
     * @param globalQps 全局 QPS 阈值
     * @param llmQps LLM 路由 QPS 阈值
     * @param llmMaxQueueingMs LLM 路由的最大排队时间
     * @return 规则初始化器
     */
    @Bean
    public SentinelRuleInitializer sentinelRuleInitializer(
            GatewayProperties properties) {
        GatewayProperties.Sentinel sentinel = properties.getSentinel();
        GatewayProperties.Agent agent = properties.getAgent();
        return new SentinelRuleInitializer(sentinel.getGlobalQps(), sentinel.getLlmQps(),
                sentinel.getLlmMaxQueueingMs(), List.of(),
                agent.getMcpSseQps(), agent.getMcpMessageQps(),
                agent.getMcpResolveQps(), agent.getMcpCallQps());
    }
}
