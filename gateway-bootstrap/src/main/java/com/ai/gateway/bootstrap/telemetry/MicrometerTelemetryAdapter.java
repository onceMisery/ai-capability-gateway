package com.ai.gateway.bootstrap.telemetry;

import com.ai.gateway.domain.port.TelemetryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tag;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Micrometer-backed adapter for the framework-neutral telemetry port. */
public final class MicrometerTelemetryAdapter implements TelemetryPort {

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    public MicrometerTelemetryAdapter(ObservationRegistry observationRegistry,
                                      MeterRegistry meterRegistry) {
        this.observationRegistry = Objects.requireNonNull(observationRegistry);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public <T> T observe(String name, Map<String, String> tags, Supplier<T> action) {
        Observation observation = Observation.start(name, observationRegistry);
        safeTags(tags).forEach(observation::lowCardinalityKeyValue);
        try {
            return observation.observe(action);
        } catch (RuntimeException | Error e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    @Override
    public void increment(String metric, Map<String, String> tags) {
        Counter.builder(metric)
                .tags(toTags(tags))
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordDuration(String metric, long durationNanos, Map<String, String> tags) {
        Timer.builder(metric)
                .tags(toTags(tags))
                .register(meterRegistry)
                .record(Math.max(0L, durationNanos), java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /** Keep only bounded, low-cardinality dimensions from application callers. */
    private Map<String, String> safeTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) return Map.of();
        return tags.entrySet().stream()
                .filter(e -> e.getKey() != null && (e.getKey().equals("resource")
                        || e.getKey().equals("outcome") || e.getKey().equals("protocol")))
                .filter(e -> e.getValue() != null)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> e.getValue().length() > 64 ? e.getValue().substring(0, 64) : e.getValue(),
                        (a, b) -> a));
    }

    private Iterable<Tag> toTags(Map<String, String> tags) {
        return safeTags(tags).entrySet().stream()
                .map(e -> Tag.of(e.getKey(), e.getValue()))
                .toList();
    }
}
