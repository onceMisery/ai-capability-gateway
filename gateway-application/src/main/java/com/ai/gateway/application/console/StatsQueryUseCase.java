package com.ai.gateway.application.console;

import com.ai.gateway.domain.port.StatsQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Use case for querying aggregated statistics from the admin console.
 *
 * <p>Provides time-series and capability-level aggregations used by
 * the monitoring dashboard. Delegates to the {@link StatsQueryPort}
 * for the actual data retrieval.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @since 0.1.0
 */
public final class StatsQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(StatsQueryUseCase.class);

    private final StatsQueryPort statsQueryPort;

    /**
     * Constructs a new StatsQueryUseCase.
     *
     * @param statsQueryPort the stats query port
     */
    public StatsQueryUseCase(StatsQueryPort statsQueryPort) {
        this.statsQueryPort = Objects.requireNonNull(statsQueryPort);
    }

    /**
     * Returns time-series data grouped by result code.
     *
     * @param fromEpochMs the start of the time range (inclusive, epoch millis)
     * @param toEpochMs the end of the time range (exclusive, epoch millis)
     * @return time-series data points; never {@code null}
     */
    public List<Map<String, Object>> timeSeriesByResultCode(long fromEpochMs, long toEpochMs) {
        return statsQueryPort.timeSeriesByResultCode(fromEpochMs, toEpochMs);
    }

    /**
     * Returns per-capability statistics.
     *
     * @param fromEpochMs the start of the time range (inclusive, epoch millis)
     * @param toEpochMs the end of the time range (exclusive, epoch millis)
     * @return per-capability statistics; never {@code null}
     */
    public List<Map<String, Object>> capabilityStats(long fromEpochMs, long toEpochMs) {
        return statsQueryPort.capabilityStats(fromEpochMs, toEpochMs);
    }
}
