package com.ai.gateway.adapter.postgresql.outbox;

import com.ai.gateway.domain.port.OutboxPort.OutboxEvent;
import com.ai.gateway.domain.port.OutboxExporterPort;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Background relay that polls the transactional outbox and exports events
 * to an external sink.
 *
 * <p>The relay implements at-least-once delivery semantics: events are
 * polled from the outbox, exported to the downstream system, and marked as
 * exported only on success. If the downstream system is unavailable, events
 * accumulate locally and an alert is raised — events are never discarded
 *.</p>
 *
 * <p><b>Backpressure:</b> if the local backlog exceeds a configurable
 * threshold, a warning is logged on each poll cycle. The relay does not
 * discard events; it continues to retry until the downstream system recovers.</p>
 *
 * <p>The actual export logic (e.g., publishing to Kafka, sending to SIEM)
 * is delegated to {@link #export(OutboxEvent)}, which provides a default
 * logging implementation. Subclasses or configuration can override this
 * to integrate with the actual external sink.</p>
 *
 * @since 0.1.0
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_BACKLOG_ALERT_THRESHOLD = 1000;

    private static final String SQL_COUNT_PENDING =
            "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'";

    private final JdbcOutboxPort outboxPort;
    private final JdbcTemplate jdbcTemplate;
    private final int batchSize;
    private final int backlogAlertThreshold;
    private final OutboxExporterPort exporter;

    /**
     * Constructs a new OutboxRelay with default configuration.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     */
    @org.springframework.beans.factory.annotation.Autowired
    public OutboxRelay(JdbcTemplate jdbcTemplate, ObjectProvider<OutboxExporterPort> exporterProvider) {
        this(jdbcTemplate, exporterProvider.getIfAvailable(),
                DEFAULT_BATCH_SIZE, DEFAULT_BACKLOG_ALERT_THRESHOLD);
    }

    public OutboxRelay(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null, DEFAULT_BATCH_SIZE, DEFAULT_BACKLOG_ALERT_THRESHOLD);
    }

    /**
     * Constructs a new OutboxRelay with custom configuration.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     * @param batchSize the maximum number of events to poll per cycle
     * @param backlogAlertThreshold the pending count threshold for backpressure alerts
     */
    public OutboxRelay(JdbcTemplate jdbcTemplate, int batchSize, int backlogAlertThreshold) {
        this(jdbcTemplate, null, batchSize, backlogAlertThreshold);
    }

    public OutboxRelay(JdbcTemplate jdbcTemplate, OutboxExporterPort exporter,
                       int batchSize, int backlogAlertThreshold) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.jdbcTemplate = jdbcTemplate;
        this.outboxPort = new JdbcOutboxPort(jdbcTemplate);
        this.batchSize = batchSize;
        this.backlogAlertThreshold = backlogAlertThreshold;
        this.exporter = exporter;
    }

    /**
     * Periodically polls the outbox and exports pending events.
     *
     * <p>Runs every 5 seconds by default. Each cycle:</p>
     * <ol>
     * <li>Checks the pending backlog and alerts if above threshold.</li>
     * <li>Polls a batch of unexported events.</li>
     * <li>Exports each event to the external sink.</li>
     * <li>Marks successfully exported events as exported.</li>
     * </ol>
     */
    @Scheduled(fixedDelay = 5000)
    public void relay() {
        checkBacklog();

        if (exporter == null) {
            log.debug("Outbox export is disabled; pending events remain available for a configured exporter");
            return;
        }

        List<OutboxEvent> events = outboxPort.pollUnexported(batchSize);
        if (events.isEmpty()) {
            return;
        }

        log.debug("OutboxRelay polled {} events", events.size());

        int exported = 0;
        int failed = 0;
        for (OutboxEvent event : events) {
            try {
                exporter.export(event);
                outboxPort.markExported(event.id());
                exported++;
            } catch (Exception e) {
                log.error("Failed to export outbox event id={}, type={}: {}",
                        event.id(), event.eventType(), e.getMessage(), e);
                failed++;
            }
        }

        log.debug("OutboxRelay cycle complete: exported={}, failed={}, total={}",
                exported, failed, events.size());
    }

    /**
     * Checks the pending backlog and logs an alert if above threshold.
     *
     * <p>: if the downstream system is unavailable, the local
     * Outbox accumulates and alerts; events must not be dropped.</p>
     */
    private void checkBacklog() {
        Long pendingCount = jdbcTemplate.queryForObject(SQL_COUNT_PENDING, Long.class);
        if (pendingCount != null && pendingCount > backlogAlertThreshold) {
            log.warn("Outbox backlog alert: {} pending events exceed threshold {}",
                    pendingCount, backlogAlertThreshold);
        }
    }

}
