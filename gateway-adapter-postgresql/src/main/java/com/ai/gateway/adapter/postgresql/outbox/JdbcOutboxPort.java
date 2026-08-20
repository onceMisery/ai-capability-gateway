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
 * {@link OutboxPort} 基于 PostgreSQL 的 JDBC 实现。
 *
 * <p>实现事务性 Outbox（Transactional Outbox）模式：事件与状态变更写入同一个本地事务，
 * 确保对下游消息系统或 SIEM 的至少一次（at-least-once）投递。</p>
 *
 * <p>（依赖失败策略）：若审计导出不可用，本地 Outbox 应积累并告警；事件绝不能被丢弃。终端状态
 * 的写入不能为了性能而被降级为可能丢失的异步操作。</p>
 *
 * @author cmiracle@163.com
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
     * 构造一个新的 JdbcOutboxPort。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
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
