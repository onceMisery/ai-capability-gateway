package com.ai.gateway.bootstrap.config;

import com.ai.gateway.bootstrap.ratelimit.SentinelRateLimiterAdapter;
import com.ai.gateway.bootstrap.ratelimit.SentinelRuleInitializer;
import com.ai.gateway.domain.port.RateLimiterPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Conditional Spring wiring for the Sentinel rate-limiting engine.
 *
 * <p>Activated only when {@code gateway.ratelimit.provider=sentinel}. When
 * inactive, the always-allow stub {@link RateLimiterPort} (registered by
 * {@code StubRateLimitConfiguration}) is used.</p>
 *
 * <p>Per the tech-selection doc §10, Sentinel is merged into the bootstrap
 * module (not a separate adapter) because it implements a single port with a
 * small footprint and is a cross-cutting concern naturally assembled at
 * startup.</p>
 *
 * @since 0.1.0
 */
@Configuration
@ConditionalOnProperty(name = "gateway.ratelimit.provider", havingValue = "sentinel")
public class SentinelRateLimitConfiguration {

    /**
     * The Sentinel {@link RateLimiterPort} implementation.
     *
     * @return the Sentinel rate limiter adapter
     */
    @Bean
    public RateLimiterPort rateLimiterPort() {
        return new SentinelRateLimiterAdapter();
    }

    /**
     * Loads the hardcoded Sentinel rules at startup.
     *
     * @param globalQps the global QPS threshold
     * @param llmQps the LLM routing QPS threshold
     * @param llmMaxQueueingMs the maximum queueing time for LLM routing
     * @return the rule initializer
     */
    @Bean
    public SentinelRuleInitializer sentinelRuleInitializer(
            @Value("${gateway.sentinel.global-qps:2000}") double globalQps,
            @Value("${gateway.sentinel.llm-qps:20}") double llmQps,
            @Value("${gateway.sentinel.llm-max-queueing-ms:2000}") int llmMaxQueueingMs) {
        return new SentinelRuleInitializer(globalQps, llmQps, llmMaxQueueingMs, List.of());
    }
}
