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
 * {@link StatsQueryPort} 基于 PostgreSQL 的 JDBC 实现。
 *
 * <p>对 {@code audit_event} 表执行聚合查询，为管理控制台监控面板生成时间序列与能力级别的
 * 统计信息。结果使用 Caffeine 缓存 30 秒。</p>
 *
 * @author cmiracle@163.com
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

    // 统计查询的 30 秒缓存
    private final LoadingCache<StatsCacheKey, List<Map<String, Object>>> statsCache;

    /**
     * 构造一个新的 JdbcStatsQueryAdapter。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
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
        // 时间序列使用按桶（bucket）查询
        Timestamp from = Timestamp.from(Instant.ofEpochMilli(fromEpochMs));
        Timestamp to = Timestamp.from(Instant.ofEpochMilli(toEpochMs));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SQL_TIME_SERIES_BUCKETED, from, to);

        // 转换为 { time, resultCode, count } 格式
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> point = new HashMap<>();
            // bucket 是 date_trunc 生成的 Timestamp
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
     * 统计查询的缓存键。
     */
    private record StatsCacheKey(String queryType, long fromEpochMs, long toEpochMs) {
        StatsCacheKey {
            Objects.requireNonNull(queryType, "queryType must not be null");
        }
    }
}