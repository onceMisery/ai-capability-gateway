package com.ai.gateway.adapter.postgresql.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Scheduled data retention cleanup for expired interaction sessions and
 * aged audit events.
 *
 * <p>Runs periodically to delete:</p>
 * <ul>
 * <li><b>Expired NL interactions:</b> records where {@code expires_at}
 * has passed. These are short-lived clarification sessions
 * that are no longer valid.</li>
 * <li><b>Aged audit events:</b> records older than the configurable
 * retention period (default 90 days). Audit events are exported to
 * an external SIEM via the outbox before deletion, so the business
 * table can be pruned to control storage growth.</li>
 * </ul>
 *
 * <p>The retention period for audit events is configurable. In production,
 * this value should be set based on compliance requirements and the
 * capacity conclusions from .</p>
 *
 * @since 0.1.0
 */
@Component
public class DataRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionScheduler.class);

    private static final int DEFAULT_AUDIT_RETENTION_DAYS = 90;

    private static final String SQL_DELETE_EXPIRED_INTERACTIONS =
            "DELETE FROM nl_interaction WHERE expires_at < NOW()";

    private static final String SQL_DELETE_AGED_AUDIT_EVENTS =
            "DELETE FROM audit_event ae " +
            "WHERE ae.timestamp < (NOW() - ? * INTERVAL '1 day') " +
            "AND EXISTS (SELECT 1 FROM outbox_event oe " +
            "WHERE oe.audit_event_id = ae.event_id AND oe.status = 'EXPORTED')";

    private final JdbcTemplate jdbcTemplate;
    private final int auditRetentionDays;

    /**
     * Constructs a new DataRetentionScheduler with the default 90-day
     * audit retention period.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DataRetentionScheduler(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, DEFAULT_AUDIT_RETENTION_DAYS);
    }

    /**
     * Constructs a new DataRetentionScheduler with a custom audit retention
     * period.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     * @param auditRetentionDays the number of days to retain audit events
     * before deletion
     */
    public DataRetentionScheduler(JdbcTemplate jdbcTemplate, int auditRetentionDays) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.auditRetentionDays = auditRetentionDays;
    }

    /**
     * Periodically cleans up expired interaction sessions and aged audit
     * events.
     *
     * <p>Runs every 24 hours by default. The cleanup is idempotent and
     * safe to run concurrently — each DELETE is an independent operation.</p>
     */
    @Scheduled(fixedDelay = 86_400_000)
    public void cleanup() {
        cleanupExpiredInteractions();
        cleanupAgedAuditEvents();
    }

    /**
     * Deletes expired NL interaction sessions.
     *
     * <p>: clarification interactions have a short TTL. Expired
     * sessions are no longer valid and can be safely removed.</p>
     */
    private void cleanupExpiredInteractions() {
        try {
            int deleted = jdbcTemplate.update(SQL_DELETE_EXPIRED_INTERACTIONS);
            if (deleted > 0) {
                log.info("DataRetention: deleted {} expired nl_interaction records", deleted);
            }
        } catch (Exception e) {
            log.error("DataRetention: failed to clean expired nl_interaction records: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Deletes audit events older than the retention period.
     *
     * <p>: audit events are exported to an external SIEM via
     * the outbox before deletion. The business table can be pruned to
     * control storage growth. The retention period is configurable based
     * on compliance requirements and capacity conclusions.</p>
     */
    private void cleanupAgedAuditEvents() {
        try {
            int deleted = jdbcTemplate.update(SQL_DELETE_AGED_AUDIT_EVENTS, auditRetentionDays);
            if (deleted > 0) {
                log.info("DataRetention: deleted {} audit_event records older than {} days",
                        deleted, auditRetentionDays);
            }
        } catch (Exception e) {
            log.error("DataRetention: failed to clean aged audit_event records: {}",
                    e.getMessage(), e);
        }
    }
}
