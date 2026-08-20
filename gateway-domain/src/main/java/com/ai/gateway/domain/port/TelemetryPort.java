package com.ai.gateway.domain.port;

import java.util.Map;
import java.util.function.Supplier;

/** Framework-neutral port for low-cardinality metrics and observations. */
public interface TelemetryPort {

    <T> T observe(String name, Map<String, String> tags, Supplier<T> action);

    void increment(String metric, Map<String, String> tags);

    void recordDuration(String metric, long durationNanos, Map<String, String> tags);

    /** Records the latest value of a bounded runtime resource. */
    void recordValue(String metric, long value, Map<String, String> tags);
}
