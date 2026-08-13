package com.ai.gateway.application.resilience;

import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientAdaptersTest {

    @Test
    void llmRateLimitFailsFastWithStableErrorCode() {
        AtomicInteger calls = new AtomicInteger();
        LlmRouterPort delegate = (text, candidates) -> {
            calls.incrementAndGet();
            return new com.ai.gateway.domain.model.ModelDecision.NoMatchDecision("NONE");
        };
        ResilientLlmRouter router = new ResilientLlmRouter(delegate,
                new RateLimiterManager((dimension, key, permits) -> false),
                new CircuitBreakerManager(), new BulkheadManager(), new RecordingTelemetry());

        assertThatThrownBy(() -> router.route("query", List.of(candidate())))
                .isInstanceOf(LlmRouterPort.LlmRoutingException.class)
                .extracting(error -> ((LlmRouterPort.LlmRoutingException) error).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void llmFailureTripsCircuitAndSkipsSecondProviderCall() {
        AtomicInteger calls = new AtomicInteger();
        LlmRouterPort delegate = (text, candidates) -> {
            calls.incrementAndGet();
            throw new LlmRouterPort.LlmRoutingException(
                    ErrorCode.LLM_UNAVAILABLE, "down");
        };
        ResilientLlmRouter router = new ResilientLlmRouter(delegate,
                new RateLimiterManager((dimension, key, permits) -> true),
                new CircuitBreakerManager(1, 60_000L, 1, 1),
                new BulkheadManager(), new RecordingTelemetry());

        assertThatThrownBy(() -> router.route("first", List.of(candidate())))
                .isInstanceOf(LlmRouterPort.LlmRoutingException.class);
        assertThatThrownBy(() -> router.route("second", List.of(candidate())))
                .isInstanceOf(LlmRouterPort.LlmRoutingException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void providerRateLimitReturnsStructuredFailureWithoutInvocation() {
        CountingInvocationAdapter delegate = new CountingInvocationAdapter();
        ResilientInvocationAdapter adapter = new ResilientInvocationAdapter(delegate,
                new RateLimiterManager((dimension, key, permits) -> false),
                new CircuitBreakerManager(), new BulkheadManager(), new RecordingTelemetry());

        InvocationResult result = adapter.invoke(org.mockito.Mockito.mock(InvocationRequest.class));

        assertThat(result.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(delegate.calls).isZero();
    }

    @Test
    void successfulCallsEmitObservationsCountersAndDurations() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        ResilientLlmRouter router = new ResilientLlmRouter(
                (text, candidates) -> new com.ai.gateway.domain.model.ModelDecision.NoMatchDecision("NONE"),
                new RateLimiterManager((dimension, key, permits) -> true),
                new CircuitBreakerManager(), new BulkheadManager(), telemetry);

        router.route("query", List.of(candidate()));

        assertThat(telemetry.observations).contains("llm.route");
        assertThat(telemetry.counters).contains("gateway.llm.calls");
        assertThat(telemetry.durations).contains("gateway.llm.duration");
    }

    private static LlmRouterPort.LlmCandidate candidate() {
        return new LlmRouterPort.LlmCandidate("cap_1", "Query", "Query",
                List.of(), List.of(), List.of(), Map.of());
    }

    private static final class RecordingTelemetry implements TelemetryPort {
        final List<String> observations = new ArrayList<>();
        final List<String> counters = new ArrayList<>();
        final List<String> durations = new ArrayList<>();

        @Override
        public <T> T observe(String name, Map<String, String> tags, Supplier<T> action) {
            observations.add(name);
            return action.get();
        }

        @Override
        public void increment(String metric, Map<String, String> tags) {
            counters.add(metric);
        }

        @Override
        public void recordDuration(String metric, long durationNanos,
                                   Map<String, String> tags) {
            durations.add(metric);
        }
    }

    private static final class CountingInvocationAdapter implements InvocationAdapter {
        int calls;
        @Override public Protocol protocol() { return Protocol.DUBBO; }
        @Override public ValidationReport validate(ProtocolBinding binding) {
            return ValidationReport.success();
        }
        @Override public InvocationResult invoke(InvocationRequest request) {
            calls++;
            return new InvocationResult(Map.of("ok", true), "OK", null, null, Map.of());
        }
    }
}
