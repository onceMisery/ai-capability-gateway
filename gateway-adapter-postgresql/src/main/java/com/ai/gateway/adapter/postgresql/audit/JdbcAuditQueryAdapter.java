package com.ai.gateway.adapter.postgresql.audit;

import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.AuditQueryCriteria;
import com.ai.gateway.domain.port.AuditQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link AuditQueryPort} 基于 PostgreSQL 的 JDBC 实现。
 *
 * <p>根据提供的查询条件动态构造 WHERE 子句来查询 {@code audit_event} 表。结果按时间戳
 * 降序（最新在前）排序并分页。</p>
 *
 * @author cmiracle@163.com
 * @see AuditQueryPort
 * @since 0.1.0
 */
@Repository
public class JdbcAuditQueryAdapter implements AuditQueryPort {

    private static final String BASE_SELECT =
            "SELECT event_id, event_type, timestamp, subject_digest, org_id, " +
            "request_id, operation_id, capability_id, capability_version, " +
            "manifest_digest, snapshot_version, policy_decision_id, " +
            "model_prompt_version, result_code, duration_ms, details " +
            "FROM audit_event";

    private static final String BASE_COUNT = "SELECT COUNT(*) FROM audit_event";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造一个新的 JdbcAuditQueryAdapter。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     */
    public JdbcAuditQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public List<AuditEvent> query(AuditQueryCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");

        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> params = new ArrayList<>();
        buildWhereClause(sql, params, criteria);
        sql.append(" ORDER BY timestamp DESC");
        sql.append(" LIMIT ? OFFSET ?");
        params.add(criteria.size());
        params.add((criteria.page() - 1) * criteria.size());

        return jdbcTemplate.query(sql.toString(), auditEventRowMapper(), params.toArray());
    }

    @Override
    public long count(AuditQueryCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");

        StringBuilder sql = new StringBuilder(BASE_COUNT);
        List<Object> params = new ArrayList<>();
        buildWhereClause(sql, params, criteria);

        Long result = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return result != null ? result : 0L;
    }

    private void buildWhereClause(StringBuilder sql, List<Object> params, AuditQueryCriteria criteria) {
        List<String> conditions = new ArrayList<>();

        if (criteria.eventType() != null) {
            conditions.add("event_type = ?");
            params.add(criteria.eventType());
        }
        if (criteria.capabilityId() != null) {
            conditions.add("capability_id = ?");
            params.add(criteria.capabilityId());
        }
        if (criteria.requestId() != null) {
            conditions.add("request_id = ?");
            params.add(criteria.requestId());
        }
        if (criteria.resultCode() != null) {
            conditions.add("result_code = ?");
            params.add(criteria.resultCode());
        }
        if (criteria.from() != null) {
            conditions.add("timestamp >= ?");
            params.add(Timestamp.from(criteria.from()));
        }
        if (criteria.to() != null) {
            conditions.add("timestamp < ?");
            params.add(Timestamp.from(criteria.to()));
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", conditions));
        }
    }

    private static RowMapper<AuditEvent> auditEventRowMapper() {
        return new RowMapper<AuditEvent>() {
            @Override
            public AuditEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
                return new AuditEvent(
                        String.valueOf(rs.getLong("event_id")),
                        rs.getString("event_type"),
                        rs.getTimestamp("timestamp").toInstant(),
                        rs.getString("subject_digest"),
                        rs.getLong("org_id"),
                        rs.getString("request_id"),
                        rs.getString("operation_id"),
                        rs.getString("capability_id"),
                        rs.getString("capability_version"),
                        rs.getString("manifest_digest"),
                        rs.getLong("snapshot_version"),
                        rs.getString("policy_decision_id"),
                        rs.getString("model_prompt_version"),
                        rs.getString("result_code"),
                        rs.getLong("duration_ms"),
                        rs.getString("details")
                );
            }
        };
    }
}
