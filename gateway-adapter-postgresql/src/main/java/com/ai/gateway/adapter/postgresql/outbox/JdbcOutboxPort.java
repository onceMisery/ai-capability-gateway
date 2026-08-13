package com.ai.gateway.adapter.postgresql.outbox;

import com.ai.gateway.adapter.postgresql.JsonbSupport;
import com.ai.gateway.domain.port.OutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * JDBC implementation of {@link OutboxPort} backed by PostgreSQL.
 *
 * <p>Implements the Transactional Outbox pattern: events are
 * written in the same local transaction as state changes, ensuring
 * at-least-once delivery to the downstream message system or SIEM.</p>
 *
 * <p>(Dependency Failure Strategy): if the audit export is
 * unavailable, the local Outbox accumulates and alerts; events must not be
 * dropped. The terminal state write must not be downgraded to a lossy
 * async operation for performance.</p>
 *
 * @see OutboxPort
 * @since 0.1.0
 */
@Repository
public class JdbcOutboxPort implements OutboxPort {

    private static final String SQL_INSERT =
            "INSERT INTO outbox_event (event_type, payload) VALUES (?, ?::jsonb)";

    private static final String SQL_POLL_UNEXPORTED =
            "SELECT id, event_type, payload, created_at FROM outbox_event " +
            "WHERE status = 'PENDING' ORDER BY id LIMIT ?";

    private static final String SQL_MARK_EXPORTED =
            "UPDATE outbox_event SET status = 'EXPORTED', exported_at = NOW() WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs a new JdbcOutboxPort.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     */
    public JdbcOutboxPort(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public void publish(String eventType, String payloadJson) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payloadJson, "payloadJson must not be null");

        jdbcTemplate.update(SQL_INSERT, ps -> {
            ps.setString(1, eventType);
            JsonbSupport.setJsonb(ps, 2, payloadJson);
        });
    }

    @Override
    public List<OutboxEvent> pollUnexported(int batchSize) {
        if (batchSize <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(SQL_POLL_UNEXPORTED, outboxRowMapper(), batchSize);
    }

    @Override
    public void markExported(long eventId) {
        jdbcTemplate.update(SQL_MARK_EXPORTED, eventId);
    }

    private static RowMapper<OutboxEvent> outboxRowMapper() {
        return (rs, rowNum) -> {
            long id = rs.getLong("id");
            String eventType = rs.getString("event_type");
            String payload = rs.getString("payload");
            Timestamp createdAtTs = rs.getTimestamp("created_at");
            Instant createdAt = createdAtTs != null ? createdAtTs.toInstant() : Instant.now();
            return new OutboxEvent(id, eventType, payload, createdAt);
        };
    }
}
