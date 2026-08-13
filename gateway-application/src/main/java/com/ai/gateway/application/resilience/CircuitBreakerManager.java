package com.ai.gateway.application.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Provider/Capability circuit breaker manager.
 *
 * <p>The circuit breaker prevents cascading failures by stopping requests
 * to a Provider or Capability that is consistently failing. Each
 * Provider/Capability has its own independent circuit breaker — a failure
 * on one capability MUST NOT trip the breaker for a semantically similar
 * other capability.</p>
 *
 * <p><strong>States:</strong></p>
 * <ul>
 * <li><strong>CLOSED</strong> — all requests are allowed. Failures are
 * counted; when the failure threshold is reached, the breaker trips
 * to OPEN.</li>
 * <li><strong>OPEN</strong> — all requests are rejected immediately. After
 * the recovery timeout elapses, the breaker transitions to HALF_OPEN
 * to probe the downstream service.</li>
 * <li><strong>HALF_OPEN</strong> — a limited number of probe requests are
 * allowed. If enough consecutive probes succeed, the breaker closes.
 * If any probe fails, the breaker re-opens.</li>
 * </ul>
 *
 * <p><strong>Configuration defaults:</strong></p>
 * <ul>
 * <li>Failure threshold: {@value DEFAULT_FAILURE_THRESHOLD} consecutive
 * failures.</li>
 * <li>Recovery timeout: {@value DEFAULT_RECOVERY_TIMEOUT_MS} ms.</li>
 * <li>Half-open max probe requests: {@value DEFAULT_HALF_OPEN_MAX_REQUESTS}.</li>
 * <li>Half-open success threshold: {@value DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD}
 * consecutive successes.</li>
 * </ul>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: breaker states are stored in a
 * {@link ConcurrentHashMap} and individual state transitions are
 * synchronized on the state object.</p>
 *
 * @see BulkheadManager
 * @since 0.1.0
 */
public final class CircuitBreakerManager {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerManager.class);

    /** Default consecutive failure count before tripping the breaker. */
    public static final int DEFAULT_FAILURE_THRESHOLD = 5;

    /** Default recovery timeout in milliseconds before transitioning to HALF_OPEN. */
    public static final long DEFAULT_RECOVERY_TIMEOUT_MS = 30_000L;

    /** Default maximum probe requests allowed in HALF_OPEN state. */
    public static final int DEFAULT_HALF_OPEN_MAX_REQUESTS = 3;

    /** Default consecutive successes in HALF_OPEN required to close the breaker. */
    public static final int DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD = 2;

    private final ConcurrentHashMap<String, BreakerState> breakers = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final long recoveryTimeoutMs;
    private final int halfOpenMaxRequests;
    private final int halfOpenSuccessThreshold;

    /**
     * Constructs a new CircuitBreakerManager with default thresholds.
     */
    public CircuitBreakerManager() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_RECOVERY_TIMEOUT_MS,
                DEFAULT_HALF_OPEN_MAX_REQUESTS, DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD);
    }

    /**
     * Constructs a new CircuitBreakerManager with custom thresholds.
     *
     * @param failureThreshold the consecutive failure count before tripping
     * @param recoveryTimeoutMs the recovery timeout in milliseconds
     * @param halfOpenMaxRequests the max probe requests in HALF_OPEN
     * @param halfOpenSuccessThreshold the consecutive successes to close from HALF_OPEN
     */
    public CircuitBreakerManager(int failureThreshold, long recoveryTimeoutMs,
                                  int halfOpenMaxRequests, int halfOpenSuccessThreshold) {
        this.failureThreshold = failureThreshold;
        this.recoveryTimeoutMs = recoveryTimeoutMs;
        this.halfOpenMaxRequests = halfOpenMaxRequests;
        this.halfOpenSuccessThreshold = halfOpenSuccessThreshold;
    }

    /**
     * Checks whether a request is allowed for the given Provider/Capability
     * key.
     *
     * <p>In OPEN state, requests are rejected until the recovery timeout
     * elapses. In HALF_OPEN, a limited number of probe requests are allowed.</p>
     *
     * @param providerKey the Provider/Capability key (e.g., "providerA:order.detail.query:1.0.0")
     * @return {@code true} if the request is allowed; {@code false} if the
     * breaker is open and the request should be rejected
     * @throws NullPointerException if {@code providerKey} is null
     */
    public boolean allowRequest(String providerKey) {
        Objects.requireNonNull(providerKey, "providerKey must not be null");
        BreakerState state = breakers.computeIfAbsent(providerKey, k -> new BreakerState());

        synchronized (state) {
            switch (state.currentState) {
                case CLOSED:
                    return true;
                case OPEN:
                    long elapsed = System.currentTimeMillis() - state.openedAt;
                    if (elapsed >= recoveryTimeoutMs) {
                        state.currentState = BreakerStateEnum.HALF_OPEN;
                        state.halfOpenRequestCount = 0;
                        state.halfOpenSuccessCount = 0;
                        log.info("Circuit breaker transitioned to HALF_OPEN: providerKey={}", providerKey);
                        return true;
                    }
                    log.debug("Circuit breaker OPEN: providerKey={}, elapsed={}ms", providerKey, elapsed);
                    return false;
                case HALF_OPEN:
                    if (state.halfOpenRequestCount < halfOpenMaxRequests) {
                        state.halfOpenRequestCount++;
                        return true;
                    }
                    log.debug("Circuit breaker HALF_OPEN at max probes: providerKey={}", providerKey);
                    return false;
                default:
                    return true;
            }
        }
    }

    /**
     * Records a successful call to the Provider/Capability.
     *
     * <p>In HALF_OPEN state, consecutive successes count toward closing
     * the breaker. In CLOSED state, the failure counter is reset.</p>
     *
     * @param providerKey the Provider/Capability key
     * @throws NullPointerException if {@code providerKey} is null
     */
    public void recordSuccess(String providerKey) {
        Objects.requireNonNull(providerKey, "providerKey must not be null");
        BreakerState state = breakers.get(providerKey);
        if (state == null) {
            return;
        }

        synchronized (state) {
            if (state.currentState == BreakerStateEnum.HALF_OPEN) {
                state.halfOpenSuccessCount++;
                if (state.halfOpenSuccessCount >= halfOpenSuccessThreshold) {
                    state.currentState = BreakerStateEnum.CLOSED;
                    state.failureCount = 0;
                    state.halfOpenRequestCount = 0;
                    state.halfOpenSuccessCount = 0;
                    log.info("Circuit breaker CLOSED (recovered): providerKey={}", providerKey);
                }
            } else if (state.currentState == BreakerStateEnum.CLOSED) {
                state.failureCount = 0;
            }
        }
    }

    /**
     * Records a failed call to the Provider/Capability.
     *
     * <p>In CLOSED state, consecutive failures count toward tripping the
     * breaker. In HALF_OPEN state, any failure immediately re-opens the
     * breaker.</p>
     *
     * @param providerKey the Provider/Capability key
     * @throws NullPointerException if {@code providerKey} is null
     */
    public void recordFailure(String providerKey) {
        Objects.requireNonNull(providerKey, "providerKey must not be null");
        BreakerState state = breakers.computeIfAbsent(providerKey, k -> new BreakerState());

        synchronized (state) {
            if (state.currentState == BreakerStateEnum.HALF_OPEN) {
                tripOpen(state, providerKey);
            } else if (state.currentState == BreakerStateEnum.CLOSED) {
                state.failureCount++;
                if (state.failureCount >= failureThreshold) {
                    tripOpen(state, providerKey);
                }
            }
        }
    }

    /**
     * Records a timeout when calling the Provider/Capability.
     *
     * <p>Timeouts are treated as failures: they count toward tripping the
     * breaker in CLOSED state and immediately re-open the breaker in
     * HALF_OPEN state.</p>
     *
     * @param providerKey the Provider/Capability key
     * @throws NullPointerException if {@code providerKey} is null
     */
    public void recordTimeout(String providerKey) {
        Objects.requireNonNull(providerKey, "providerKey must not be null");
        log.warn("Circuit breaker recording timeout as failure: providerKey={}", providerKey);
        recordFailure(providerKey);
    }

    /**
     * Returns the current state of the circuit breaker for the given key.
     *
     * @param providerKey the Provider/Capability key
     * @return the breaker state name ("CLOSED", "OPEN", or "HALF_OPEN")
     * @throws NullPointerException if {@code providerKey} is null
     */
    public String getBreakerState(String providerKey) {
        Objects.requireNonNull(providerKey, "providerKey must not be null");
        BreakerState state = breakers.get(providerKey);
        if (state == null) {
            return BreakerStateEnum.CLOSED.name();
        }
        synchronized (state) {
            return state.currentState.name();
        }
    }

    /**
     * Transitions the breaker to OPEN state.
     *
     * @param state the breaker state
     * @param providerKey the provider key for logging
     */
    private void tripOpen(BreakerState state, String providerKey) {
        state.currentState = BreakerStateEnum.OPEN;
        state.openedAt = System.currentTimeMillis();
        state.failureCount = 0;
        state.halfOpenRequestCount = 0;
        state.halfOpenSuccessCount = 0;
        log.warn("Circuit breaker tripped to OPEN: providerKey={}", providerKey);
    }

    /**
     * Circuit breaker state enumeration.
     */
    private enum BreakerStateEnum {
        CLOSED, OPEN, HALF_OPEN
    }

    /**
     * Mutable circuit breaker state for a single Provider/Capability.
     *
     * <p>Access to this object is synchronized by the caller using the
     * object as the monitor.</p>
     */
    private static final class BreakerState {
        BreakerStateEnum currentState = BreakerStateEnum.CLOSED;
        int failureCount = 0;
        long openedAt = 0;
        int halfOpenRequestCount = 0;
        int halfOpenSuccessCount = 0;
    }
}
