package com.ai.gateway.domain.port;

import java.util.List;
import java.util.Map;

/**
 * Port for querying aggregated statistics from the audit store.
 *
 * <p>Provides time-series and capability-level aggregations used by
 * the admin console's monitoring dashboard. The adapter typically
 * queries PostgreSQL aggregate functions or a dedicated materialized
 * view.</p>
 *
 * @since 0.1.0
 */
public interface StatsQueryPort {

    /**
     * Returns time-series counts of audit events grouped by result code
     * over the specified time range.
     *
     * <p>Each entry in the returned list represents one time bucket with
     * a result code and its count. The bucket size is determined by the
     * adapter based on the time range.</p>
     *
     * @param fromEpochMs the start of the time range (inclusive, epoch millis)
     * @param toEpochMs the end of the time range (exclusive, epoch millis)
     * @return time-series data points; never {@code null}
     */
    List<Map<String, Object>> timeSeriesByResultCode(long fromEpochMs, long toEpochMs);

    /**
     * Returns aggregated success/failure counts per capability over the
     * specified time range.
     *
     * <p>Each entry in the returned list contains the capability ID,
     * success count, failure count, and average duration.</p>
     *
     * @param fromEpochMs the start of the time range (inclusive, epoch millis)
     * @param toEpochMs the end of the time range (exclusive, epoch millis)
     * @return per-capability statistics; never {@code null}
     */
    List<Map<String, Object>> capabilityStats(long fromEpochMs, long toEpochMs);
}
