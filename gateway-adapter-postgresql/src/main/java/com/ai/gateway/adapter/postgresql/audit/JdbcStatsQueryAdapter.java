package com.ai.gateway.adapter.postgresql.audit;

import com.ai.gateway.domain.port.StatsQueryPort;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * JDBC implementation of {@link StatsQueryPort} backed by PostgreSQL.
 *
 * <p>Executes aggregate queries against the {@code audit_event} table to
 * produce time-series and capability-level statistics for the admin
 * console monitoring dashboard. Results are cached for 30 seconds
 * using Caffeine.</p>
 *
 * @see StatsQueryPort
 * @since 0.1.0
 */
@Repository
public class JdbcStatsQueryAdapter implements StatsQueryPort {

    private static final String SQL_TIME_SERIES =
            "SELECT result_code, COUNT(*) AS cnt " +
            "FROM audit_event " +
            "WHERE timestamp >= ? AND timestamp < ? " +
            "GROUP BY result_code " +
            "ORDER BY result_code";

    private static final String SQL_CAPABILITY_STATS =
            "SELECT capability_id, " +
            "  COUNT(*) AS total_calls, " +
            "  SUM(CASE WHEN result_code IN ('SUCCESS', 'REQUEST_ACCEPTED', 'STARTED') THEN 1 ELSE 0 END) AS success_count, " +
            "  SUM(CASE WHEN result_code NOT IN ('SUCCESS', 'REQUEST_ACCEPTED', 'STARTED') AND result_code IS NOT NULL THEN 1 ELSE 0 END) AS failure_count, " +
            "  AVG(duration_ms) AS avg_duration_ms " +
            "FROM audit_event " +
            "WHERE timestamp >= ? AND timestamp < ? AND capability_id IS NOT NULL " +
            "GROUP BY capability_id " +
            "ORDER BY total_calls DESC";

    private static final String SQL_TIME_SERIES_BUCKETED =
            "SELECT date_trunc('hour', timestamp) AS bucket, " +
            "  result_code, COUNT(*) AS cnt " +
            "FROM audit_event " +
            "WHERE timestamp >= ? AND timestamp < ? " +
            "GROUP BY bucket, result_code " +
            "ORDER BY bucket, result_code";

    private final JdbcTemplate jdbcTemplate;

    // 30-second cache for stats queries
    private final LoadingCache<StatsCacheKey, List<Map<String, Object>>> statsCache;

    /**
     * Constructs a new JdbcStatsQueryAdapter.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     */
    public JdbcStatsQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");

        this.statsCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(100)
                .build(this::loadStats);
    }

    @Override
    public List<Map<String, Object>> timeSeriesByResultCode(long fromEpochMs, long toEpochMs) {
        // Use bucketed query for time-series
        Timestamp from = Timestamp.from(Instant.ofEpochMilli(fromEpochMs));
        Timestamp to = Timestamp.from(Instant.ofEpochMilli(toEpochMs));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SQL_TIME_SERIES_BUCKETED, from, to);

        // Transform to { time, resultCode, count } format
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> point = new HashMap<>();
            // bucket is a Timestamp from date_trunc
            Object bucketObj = row.get("bucket");
            if (bucketObj instanceof Timestamp ts) {
                point.put("time", ts.toInstant().toEpochMilli());
            } else {
                point.put("time", fromEpochMs);
            }
            point.put("resultCode", row.get("result_code"));
            point.put("count", row.get("cnt"));
            result.add(point);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> capabilityStats(long fromEpochMs, long toEpochMs) {
        StatsCacheKey key = new StatsCacheKey("capabilityStats", fromEpochMs, toEpochMs);
        return statsCache.get(key);
    }

    private List<Map<String, Object>> loadStats(StatsCacheKey key) {
        Timestamp from = Timestamp.from(Instant.ofEpochMilli(key.fromEpochMs));
        Timestamp to = Timestamp.from(Instant.ofEpochMilli(key.toEpochMs));

        return jdbcTemplate.queryForList(SQL_CAPABILITY_STATS, from, to);
    }

    /**
     * Cache key for stats queries.
     */
    private record StatsCacheKey(String queryType, long fromEpochMs, long toEpochMs) {
        StatsCacheKey {
            Objects.requireNonNull(queryType, "queryType must not be null");
        }
    }
}