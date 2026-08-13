package com.ai.gateway.application.resilience;

import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ModelDecision;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.ai.gateway.domain.port.TelemetryPort;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies fail-fast rate limiting, circuit breaking, and bulkhead isolation to LLM calls. */
public final class ResilientLlmRouter implements LlmRouterPort {

    private static final String RESOURCE_KEY = "llm";

    private final LlmRouterPort delegate;
    private final RateLimiterManager rateLimiter;
    private final CircuitBreakerManager circuitBreaker;
    private final BulkheadManager bulkhead;
    private final TelemetryPort telemetry;

    public ResilientLlmRouter(LlmRouterPort delegate,
                              RateLimiterManager rateLimiter,
                              CircuitBreakerManager circuitBreaker,
                              BulkheadManager bulkhead,
                              TelemetryPort telemetry) {
        this.delegate = Objects.requireNonNull(delegate);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
        this.bulkhead = Objects.requireNonNull(bulkhead);
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    @Override
    public ModelDecision route(String userText, List<LlmCandidate> candidates) {
        return telemetry.observe("llm.route", Map.of("resource", RESOURCE_KEY),
                () -> routeGuarded(userText, candidates));
    }

    private ModelDecision routeGuarded(String userText, List<LlmCandidate> candidates) {
        long started = System.nanoTime();
        Map<String, String> tags = Map.of("resource", RESOURCE_KEY);
        BulkheadManager.BulkheadLease lease = null;
        try {
            if (!rateLimiter.checkAndAcquire(
                    RateLimiterManager.DIM_LLM_CONCURRENCY, RESOURCE_KEY)) {
                telemetry.increment("gateway.llm.calls", Map.of("outcome", "rate_limited"));
                throw new LlmRoutingException(ErrorCode.RATE_LIMITED,
                        "LLM capacity limit reached");
            }
            if (!circuitBreaker.allowRequest(RESOURCE_KEY)) {
                telemetry.increment("gateway.llm.calls", Map.of("outcome", "circuit_open"));
                throw new LlmRoutingException(ErrorCode.LLM_UNAVAILABLE,
                        "LLM circuit breaker is open");
            }
            lease = bulkhead.acquire(RESOURCE_KEY, 0L);
            if (!lease.acquired()) {
                telemetry.increment("gateway.llm.calls", Map.of("outcome", "bulkhead_full"));
                throw new LlmRoutingException(ErrorCode.RATE_LIMITED,
                        "LLM concurrency limit reached");
            }
            ModelDecision result = delegate.route(userText, candidates);
            circuitBreaker.recordSuccess(RESOURCE_KEY);
            telemetry.increment("gateway.llm.calls", Map.of("outcome", "success"));
            return result;
        } catch (RuntimeException e) {
            if (!(e instanceof LlmRoutingException routing
                    && routing.errorCode() == ErrorCode.RATE_LIMITED)) {
                circuitBreaker.recordFailure(RESOURCE_KEY);
                telemetry.increment("gateway.llm.calls", Map.of("outcome", "error"));
            }
            throw e;
        } finally {
            if (lease != null) bulkhead.release(lease);
            telemetry.recordDuration("gateway.llm.duration",
                    System.nanoTime() - started, tags);
        }
    }
}
