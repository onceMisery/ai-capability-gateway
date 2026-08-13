package com.ai.gateway.domain.model;

/**
 * Resilience policy for a capability's protocol invocation.
 *
 * <p>and define the per-capability resilience
 * configuration. These values are part of the confirmed Manifest and
 * are enforced by the adapter layer:</p>
 *
 * <ul>
 * <li>{@code timeoutMs} - the maximum time for a single Provider call.
 * This must not exceed the remaining deadline budget at the point
 * of invocation.</li>
 * <li>{@code retries} - the maximum number of retries for retryable
 * errors. Retries are subject to the risk level:
 * read-only operations may retry per policy; write operations
 * must follow the two-phase recovery protocol.</li>
 * <li>{@code maxConcurrent} - the concurrency bulkhead
 * limiting parallel invocations of this capability.</li>
 * <li>{@code circuitBreakerEnabled} - whether the circuit breaker
 * pattern is active for this capability, providing fast-fail
 * behavior when the Provider is consistently failing.</li>
 * </ul>
 *
 * @param timeoutMs the Provider call timeout in milliseconds
 * @param retries the maximum retry count for retryable errors
 * @param maxConcurrent the concurrency bulkhead limit
 * @param circuitBreakerEnabled whether the circuit breaker is active
 * @since 0.1.0
 */
public record ResiliencePolicy(
        long timeoutMs,
        int retries,
        int maxConcurrent,
        boolean circuitBreakerEnabled
) {
}
