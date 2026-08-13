package com.ai.gateway.application.resilience;

import com.ai.gateway.domain.port.RateLimiterPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Rate limiter manager enforcing bounded resources across all gateway
 * dimensions.
 *
 * <p>Specifies that the gateway must set at least the
 * following bounded resources:</p>
 * <ul>
 * <li><strong>Entry-level rate limiting:</strong> per-user, per-tenant,
 * and per-application request rate.</li>
 * <li><strong>LLM resource limits:</strong> LLM concurrency and token
 * budget.</li>
 * <li><strong>Per-Provider/Capability bulkhead:</strong> concurrency
 * isolation (see {@link BulkheadManager}).</li>
 * <li><strong>Request queue:</strong> queue length and queue wait time.</li>
 * <li><strong>Response bounds:</strong> maximum response body size, JSON
 * depth, and array length.</li>
 * <li><strong>Session/operation limits:</strong> clarification session
 * count and Prepare operation count.</li>
 * </ul>
 *
 * <p>When limits are reached, the gateway must return a clear rate-limit
 * or busy error — it must not queue indefinitely. This prevents resource
 * exhaustion and protects both the gateway and downstream Providers from
 * unbounded load.</p>
 *
 * <p>This class delegates to {@link RateLimiterPort} for the actual
 * permit acquisition. The {@link #release} method is a no-op for
 * time-window-based dimensions (per-user, per-tenant, etc.) since their
 * permits expire naturally. For concurrency-based dimensions (LLM
 * concurrency, bulkhead), release is handled by {@link BulkheadManager}.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: all state is delegated to the {@link RateLimiterPort}
 * implementation, which is expected to be thread-safe.</p>
 *
 * @see RateLimiterPort
 * @see BulkheadManager
 * @since 0.1.0
 */
public final class RateLimiterManager {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterManager.class);

    /** Per-user request rate dimension. */
    public static final String DIM_USER = "user";

    /** Per-tenant request rate dimension. */
    public static final String DIM_TENANT = "tenant";

    /** Per-application request rate dimension. */
    public static final String DIM_APPLICATION = "application";

    /** LLM concurrency dimension. */
    public static final String DIM_LLM_CONCURRENCY = "llm-concurrency";

    /** LLM token budget dimension. */
    public static final String DIM_LLM_TOKEN_BUDGET = "llm-token-budget";

    /** Per-Provider/Capability bulkhead dimension. */
    public static final String DIM_BULKHEAD = "bulkhead";

    /** Request queue length dimension. */
    public static final String DIM_REQUEST_QUEUE = "request-queue";

    /** Maximum response body size dimension. */
    public static final String DIM_MAX_RESPONSE_BODY = "max-response-body";

    /** Maximum JSON depth dimension. */
    public static final String DIM_JSON_DEPTH = "json-depth";

    /** Maximum array length dimension. */
    public static final String DIM_ARRAY_LENGTH = "array-length";

    /** Clarification session count dimension. */
    public static final String DIM_CLARIFICATION_SESSION = "clarification-session";

    /** Prepare operation count dimension. */
    public static final String DIM_PREPARE_OPERATION = "prepare-operation";

    private final RateLimiterPort rateLimiterPort;

    /**
     * Constructs a new RateLimiterManager with the required dependency.
     *
     * @param rateLimiterPort the port for acquiring rate-limit permits
     * @throws NullPointerException if {@code rateLimiterPort} is null
     */
    public RateLimiterManager(RateLimiterPort rateLimiterPort) {
        this.rateLimiterPort = Objects.requireNonNull(rateLimiterPort,
                "rateLimiterPort must not be null");
    }

    /**
     * Checks and acquires a single permit for the given dimension and key
     *
     * <p>When the limit is reached, this method returns {@code false}
     * immediately, enabling Fail Fast behavior rather than blocking. The
     * caller must return a clear rate-limit or busy error to the client.</p>
     *
     * @param dimension the rate-limit dimension (e.g., {@link #DIM_USER},
     * {@link #DIM_LLM_CONCURRENCY})
     * @param key the rate-limit key (e.g., user ID, tenant ID,
     * capability ID)
     * @return {@code true} if the permit was acquired; {@code false} if the
     * limit was exceeded
     * @throws NullPointerException if any argument is null
     */
    public boolean checkAndAcquire(String dimension, String key) {
        Objects.requireNonNull(dimension, "dimension must not be null");
        Objects.requireNonNull(key, "key must not be null");

        boolean acquired = rateLimiterPort.tryAcquire(dimension, key, 1);
        if (!acquired) {
            log.warn("Rate limit exceeded: dimension={}, key={}", dimension, key);
        }
        return acquired;
    }

    /**
     * Releases a previously acquired permit for the given dimension and key.
     *
     * <p>For time-window-based dimensions (per-user, per-tenant, etc.),
     * this is a no-op since permits expire naturally after the window.
     * For concurrency-based dimensions (LLM concurrency, bulkhead), release
     * is handled by {@link BulkheadManager}.</p>
     *
     * @param dimension the rate-limit dimension
     * @param key the rate-limit key
     * @throws NullPointerException if any argument is null
     */
    public void release(String dimension, String key) {
        Objects.requireNonNull(dimension, "dimension must not be null");
        Objects.requireNonNull(key, "key must not be null");

        log.debug("Release requested: dimension={}, key={} (no-op for time-window dimensions)",
                dimension, key);
    }
}
