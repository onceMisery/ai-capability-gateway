package com.ai.gateway.domain.model;

/**
 * A read-only view of the cache subsystem status.
 *
 * <p>Exposed through the admin console to show the current cache provider,
 * configuration, and snapshot cache state.</p>
 *
 * @param provider the cache provider (stub or redis)
 * @param redisAddress the Redis address (masked in production)
 * @param localTtlSeconds the L1 (Caffeine) local cache TTL in seconds
 * @param currentSnapshotVersion the currently loaded snapshot version
 * @param lastRefreshTimestamp the last snapshot refresh timestamp (epoch millis)
 * @since 0.1.0
 */
public record CacheStatus(
        String provider,
        String redisAddress,
        int localTtlSeconds,
        long currentSnapshotVersion,
        long lastRefreshTimestamp
) {

    /**
     * Compact constructor performing null checks.
     */
    public CacheStatus {
        java.util.Objects.requireNonNull(provider, "provider must not be null");
        java.util.Objects.requireNonNull(redisAddress, "redisAddress must not be null");
    }
}
