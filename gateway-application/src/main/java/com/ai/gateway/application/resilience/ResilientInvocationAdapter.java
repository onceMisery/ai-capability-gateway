package com.ai.gateway.application.resilience;

import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.TelemetryPort;

import java.util.Map;
import java.util.Objects;

/** Applies runtime resilience policy around protocol-provider invocation. */
public final class ResilientInvocationAdapter implements InvocationAdapter {

    private final InvocationAdapter delegate;
    private final RateLimiterManager rateLimiter;
    private final CircuitBreakerManager circuitBreaker;
    private final BulkheadManager bulkhead;
    private final TelemetryPort telemetry;

    public ResilientInvocationAdapter(InvocationAdapter delegate,
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
    public Protocol protocol() {
        return delegate.protocol();
    }

    @Override
    public ValidationReport validate(ProtocolBinding binding) {
        return delegate.validate(binding);
    }

    @Override
    public InvocationResult invoke(InvocationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String capability = request.capabilityId() == null ? "unknown" : request.capabilityId();
        Map<String, String> tags = Map.of("capability", capability);
        return telemetry.observe("adapter.invoke", tags,
                () -> invokeGuarded(request, capability, tags));
    }

    private InvocationResult invokeGuarded(InvocationRequest request, String key,
                                           Map<String, String> tags) {
        long started = System.nanoTime();
        BulkheadManager.BulkheadLease lease = null;
        try {
            if (!rateLimiter.checkAndAcquire(RateLimiterManager.DIM_BULKHEAD, key)) {
                telemetry.increment("gateway.provider.calls", Map.of("outcome", "rate_limited"));
                return rejected(ErrorCode.RATE_LIMITED, "Provider rate limit reached");
            }
            if (!circuitBreaker.allowRequest(key)) {
                telemetry.increment("gateway.provider.calls", Map.of("outcome", "circuit_open"));
                return rejected(ErrorCode.CAPABILITY_UNAVAILABLE,
                        "Provider circuit breaker is open");
            }
            lease = bulkhead.acquire(key, 0L);
            if (!lease.acquired()) {
                telemetry.increment("gateway.provider.calls", Map.of("outcome", "bulkhead_full"));
                return rejected(ErrorCode.RATE_LIMITED,
                        "Provider concurrency limit reached");
            }
            InvocationResult result = delegate.invoke(request);
            if (isTechnicalFailure(result.errorCode())) {
                circuitBreaker.recordFailure(key);
                telemetry.increment("gateway.provider.calls", Map.of("outcome", "error"));
            } else {
                circuitBreaker.recordSuccess(key);
                telemetry.increment("gateway.provider.calls", Map.of("outcome", "success"));
            }
            return result;
        } catch (RuntimeException e) {
            circuitBreaker.recordFailure(key);
            telemetry.increment("gateway.provider.calls", Map.of("outcome", "error"));
            throw e;
        } finally {
            if (lease != null) bulkhead.release(lease);
            telemetry.recordDuration("gateway.provider.duration",
                    System.nanoTime() - started, tags);
        }
    }

    private boolean isTechnicalFailure(ErrorCode errorCode) {
        return errorCode == ErrorCode.PROVIDER_TIMEOUT
                || errorCode == ErrorCode.PROTOCOL_ERROR
                || errorCode == ErrorCode.CAPABILITY_UNAVAILABLE;
    }

    private InvocationResult rejected(ErrorCode code, String message) {
        return new InvocationResult(null, "REJECTED", code, message, Map.of());
    }
}
