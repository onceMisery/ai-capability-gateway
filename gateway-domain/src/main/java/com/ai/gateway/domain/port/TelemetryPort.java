package com.ai.gateway.domain.port;

import java.util.Map;
import java.util.function.Supplier;

/** 面向低基数指标与观测、与框架无关的端口。 */
public interface TelemetryPort {

    <T> T observe(String name, Map<String, String> tags, Supplier<T> action);

    void increment(String metric, Map<String, String> tags);

    void recordDuration(String metric, long durationNanos, Map<String, String> tags);

    /** 记录一个有界运行时资源的最新值。 */
    void recordValue(String metric, long value, Map<String, String> tags);
}
