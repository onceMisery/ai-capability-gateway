package com.ai.gateway.domain.port;

/**
 * Port for rate limiting and backpressure.
 *
 * <p>(Rate Limiting and Backpressure) specifies that the
 * gateway must set at least the following bounded resources:</p>
 * <ul>
 * <li>Entry-level user, tenant, and application rate limiting.</li>
 * <li>LLM concurrency and token budget.</li>
 * <li>Per-Provider/Capability concurrency bulkhead.</li>
 * <li>Reference cache size.</li>
 * <li>Request queue length and queue wait time.</li>
 * <li>Maximum response body size, JSON depth, and array length.</li>
 * <li>Clarification session and Prepare operation count.</li>
 * </ul>
 *
 * <p>When limits are reached, the gateway must return a clear rate-limit
 * or busy error — it must not queue indefinitely. This prevents resource
 * exhaustion and protects both the gateway and downstream Providers from
 * unbounded load.</p>
 *
 * <p>Adapters implementing this port implement the rate-limiting strategy
 * (e.g., sliding window, token bucket, or fixed window) using an
 * in-memory or distributed store. The port is a pure abstraction with no
 * framework dependencies.</p>
 *
 * @see RateLimitDimension
 * @since 0.1.0
 */
public interface RateLimiterPort {

    /**
     * Attempts to acquire permits for the given rate-limit dimension and key.
     *
     * <p>: when the limit is reached, the gateway must return
     * a clear rate-limit or busy error. The method returns {@code false}
     * immediately if the permits cannot be acquired, enabling Fail Fast
     * behavior rather than blocking indefinitely.</p>
     *
     * @param dimension the rate-limit dimension name (e.g., "user", "tenant", "llm-concurrency")
     * @param key the rate-limit key (e.g., user ID, tenant ID, capability ID)
     * @param permits the number of permits to acquire
     * @return {@code true} if the permits were acquired; {@code false} if the limit was exceeded
     */
    boolean tryAcquire(String dimension, String key, int permits);

    /**
     * A rate-limit dimension configuration.
     *
     * <p>: each bounded resource has a maximum permit count
     * and a time window. Dimensions are configured at deployment time and
     * are not dynamically extensible via Manifest. When the maximum is
     * reached within the window, new requests are rejected with a clear
     * rate-limit error.</p>
     *
     * @param name the dimension name (e.g., "user", "tenant", "llm-concurrency")
     * @param maxPermits the maximum number of permits allowed within the window
     * @param windowSeconds the time window in seconds
     */
    record RateLimitDimension(String name, int maxPermits, long windowSeconds) {
    }
}
