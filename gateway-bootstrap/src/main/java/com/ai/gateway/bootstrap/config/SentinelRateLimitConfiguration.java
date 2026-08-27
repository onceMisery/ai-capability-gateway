package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.a2a.A2aRateLimiter;
import com.ai.gateway.adapter.mcp.McpRateLimiter;
import com.ai.gateway.bootstrap.ratelimit.SentinelRateLimiterAdapter;
import com.ai.gateway.bootstrap.ratelimit.SentinelRuleInitializer;
import com.ai.gateway.domain.port.RateLimiterPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

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
     * @param properties 网关配置
     * @return 规则初始化器
     */
    @Bean
    public SentinelRuleInitializer sentinelRuleInitializer(
            GatewayProperties properties) {
        GatewayProperties.Sentinel sentinel = properties.getSentinel();
        // 资源键直接引用限流器常量：限流规则与取用限流的代码共用同一个键，
        // 避免「配了阈值但资源名拼错，于是这条入口实际不限流」这类静默失效。
        java.util.Map<String, Double> dimensionQps = new java.util.LinkedHashMap<>();
        GatewayProperties.Agent agent = properties.getAgent();
        dimensionQps.put(McpRateLimiter.SSE, agent.getMcpSseQps());
        dimensionQps.put(McpRateLimiter.MESSAGE, agent.getMcpMessageQps());
        dimensionQps.put(McpRateLimiter.RESOLVE, agent.getMcpResolveQps());
        dimensionQps.put(McpRateLimiter.CALL, agent.getMcpCallQps());
        dimensionQps.put(McpRateLimiter.NOTIFY, agent.getMcpNotifyQps());
        dimensionQps.putAll(a2aDimensionQps(properties.getA2a()));
        return new SentinelRuleInitializer(sentinel.getGlobalQps(), sentinel.getLlmQps(),
                sentinel.getLlmMaxQueueingMs(), List.of(), dimensionQps);
    }

    /**
     * A2A 三个暴露面维度的阈值。
     *
     * <p>只在服务端实际装配时才注册规则：A2A 关闭的部署不存在这三个入口，
     * 此时校验它们的阈值只会把一个不影响任何行为的配置值变成启动失败。</p>
     *
     * <p>两张卡片共用 {@code card-qps} 但各占一个资源键，因此各自计数：
     * 匿名可达的公开卡被刷满时，受信 peer 的扩展卡请求不该跟着被拒。</p>
     *
     * @param a2a A2A 配置节点
     * @return 资源键到阈值的映射；服务端未启用时为空
     */
    static Map<String, Double> a2aDimensionQps(GatewayProperties.A2a a2a) {
        if (!A2aConfiguration.ServerEnabledCondition.serverEnabled(
                a2a.isEnabled(), a2a.getMode())) {
            return Map.of();
        }
        Map<String, Double> qps = new java.util.LinkedHashMap<>();
        qps.put(A2aRateLimiter.PUBLIC_CARD, a2a.getCardQps());
        qps.put(A2aRateLimiter.EXTENDED_CARD, a2a.getCardQps());
        qps.put(A2aRateLimiter.TASK, a2a.getTaskQps());
        return qps;
    }
}
