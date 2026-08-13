package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * A read-only view of the gateway's non-sensitive runtime configuration.
 *
 * <p>Exposed through the admin console for operational visibility.
 * Sensitive values (JWT secrets, API keys, database passwords) are
 * never included.</p>
 *
 * @param environment the deployment environment
 * @param authProvider the authentication provider (stub or sa-token)
 * @param cacheProvider the cache provider (stub or redis)
 * @param ratelimitProvider the rate-limit provider (stub or sentinel)
 * @param maxRequestSizeBytes the maximum request body size
 * @param maxResponseBytes the maximum response body size
 * @param defaultTimeoutMs the default timeout in milliseconds
 * @param rateLimits the rate limit configuration
 * @param auditConfig the audit batch writer configuration
 * @param snapshotConfig the snapshot configuration
 * @param sentinelConfig the Sentinel rule thresholds (if sentinel is active)
 * @since 0.1.0
 */
public record GatewayConfig(
        String environment,
        String authProvider,
        String cacheProvider,
        String ratelimitProvider,
        int maxRequestSizeBytes,
        long maxResponseBytes,
        int defaultTimeoutMs,
        Map<String, Object> rateLimits,
        Map<String, Object> auditConfig,
        Map<String, Object> snapshotConfig,
        Map<String, Object> sentinelConfig
) {

    /**
     * Compact constructor performing defensive copying.
     */
    public GatewayConfig {
        java.util.Objects.requireNonNull(environment, "environment must not be null");
        java.util.Objects.requireNonNull(authProvider, "authProvider must not be null");
        java.util.Objects.requireNonNull(cacheProvider, "cacheProvider must not be null");
        java.util.Objects.requireNonNull(ratelimitProvider, "ratelimitProvider must not be null");
        rateLimits = rateLimits == null ? Map.of() : Map.copyOf(rateLimits);
        auditConfig = auditConfig == null ? Map.of() : Map.copyOf(auditConfig);
        snapshotConfig = snapshotConfig == null ? Map.of() : Map.copyOf(snapshotConfig);
        sentinelConfig = sentinelConfig == null ? Map.of() : Map.copyOf(sentinelConfig);
    }
}
