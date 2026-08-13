package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.port.RateLimiterPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default (stub) rate-limiting wiring for the initial release.
 *
 * <p>Active when {@code gateway.ratelimit.provider} is unset or {@code stub}.
 * Selecting {@code gateway.ratelimit.provider=sentinel} activates
 * {@code SentinelRateLimitConfiguration} instead.</p>
 *
 * <p>The stub always allows requests — no rate limiting is enforced —
 * matching the initial-release degradation rule.</p>
 *
 * @since 0.1.0
 */
@Configuration
@ConditionalOnProperty(name = "gateway.ratelimit.provider", havingValue = "stub", matchIfMissing = true)
public class StubRateLimitConfiguration {

    /**
     * Stub {@link RateLimiterPort} that always allows requests.
     *
     * @return the always-allow rate limiter
     */
    @Bean
    public RateLimiterPort rateLimiterPort() {
        return (dimension, key, permits) -> true;
    }
}
