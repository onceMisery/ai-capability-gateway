package com.ai.gateway.adapter.postgresql.audit;

import com.ai.gateway.adapter.postgresql.JsonbSupport;
import com.ai.gateway.domain.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Micro-batching audit writer implementing the application-level group-commit
 * pattern.
 *
 * <p>Does not change the "calling thread must wait until its audit event is
 * persisted" synchronous semantic. Instead, multiple concurrent requests
 * share the same database transaction and network round-trip by batching
 * their audit inserts together.</p>
 *
 * <p>Key design decisions:</p>
 * <ul>
 * <li><b>Event queue:</b> {@link ArrayBlockingQueue} with bounded capacity.
 * When full, new events are rejected to prevent unbounded growth.</li>
 * <li><b>Commit threads:</b> One dedicated thread per event type
 * (REQUEST_ACCEPTED, STARTED, TERMINAL), preventing cross-phase
 * blocking.</li>
 * <li><b>Batch trigger:</b> {@code maxBatchSize} (default 50) or
 * {@code maxWaitMillis} (default 5ms), whichever comes first.</li>
 * <li><b>Batch write:</b> {@code PreparedStatement.addBatch()} +
 * {@code executeBatch()} in a single transaction.</li>
 * <li><b>Caller release:</b> Each event carries a {@link CompletableFuture}
 * that completes when the batch is committed. The calling thread
 * blocks until completion.</li>
 * <li><b>Durability:</b> {@code synchronous_commit = on} (PostgreSQL
 * default) — no durability degradation.</li>
 * </ul>
 *
 * <p>Crash safety: if the commit thread is mid-batch when the process
 * crashes, the uncommitted events are lost, but the corresponding caller
 * threads also terminate (no response is sent to clients), so there is no
 * silent inconsistency — identical to single-row synchronous writes.</p>
 *
 * @since 0.1.0
 */
public class AuditBatchWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditBatchWriter.class);

    private static final String SQL_INSERT_AUDIT_AND_OUTBOX =
            "WITH inserted_audit AS (" +
            "INSERT INTO audit_event (event_type, timestamp, subject_digest, org_id, " +
            "request_id, operation_id, capability_id, capability_version, manifest_digest, " +
            "snapshot_version, policy_decision_id, model_prompt_version, result_code, " +
            "duration_ms, details) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) " +
            "RETURNING event_id) " +
            "INSERT INTO outbox_event (event_type, payload, audit_event_id) " +
            "SELECT ?, ?::jsonb, event_id FROM inserted_audit";

    private static final String SQL_INSERT_EXECUTION_RECORD =
            "INSERT INTO execution_record (execution_id, principal_digest, org_id, " +
            "capability_id, capability_version, snapshot_version, status, routing_summary) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'STARTED', ?::jsonb) " +
            "ON CONFLICT (execution_id) DO UPDATE SET status = EXCLUDED.status";

    private static final String SQL_UPDATE_EXECUTION_RECORD =
            "UPDATE execution_record SET status = ? WHERE execution_id = ?";

    private final PhaseWriter requestAcceptedWriter;
    private final PhaseWriter startedWriter;
    private final PhaseWriter terminalWriter;

    /**
     * Constructs a new AuditBatchWriter with default configuration.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     */
    public AuditBatchWriter(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, TransactionOperations.withoutTransaction(),
                2000, 50, 5L, 200L, 10_000L);
    }

    public AuditBatchWriter(JdbcTemplate jdbcTemplate,
                            PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, new TransactionTemplate(transactionManager),
                2000, 50, 5L, 200L, 10_000L);
    }

    /**
     * Constructs a new AuditBatchWriter with custom configuration.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     * @param queueCapacity the bounded queue capacity per phase
     * @param maxBatchSize the maximum number of events per batch
     * @param maxWaitMillis the maximum wait time before flushing a partial batch
     * @param enqueueTimeoutMillis the timeout for enqueueing when the queue is full
     * @param shutdownDrainTimeoutMillis the timeout for draining on shutdown
     */
    public AuditBatchWriter(JdbcTemplate jdbcTemplate, int queueCapacity, int maxBatchSize,
                            long maxWaitMillis, long enqueueTimeoutMillis,
                            long shutdownDrainTimeoutMillis) {
        this(jdbcTemplate, TransactionOperations.withoutTransaction(), queueCapacity,
                maxBatchSize, maxWaitMillis, enqueueTimeoutMillis, shutdownDrainTimeoutMillis);
    }

    AuditBatchWriter(JdbcTemplate jdbcTemplate, TransactionOperations transactionOperations,
                     int queueCapacity, int maxBatchSize, long maxWaitMillis,
                     long enqueueTimeoutMillis, long shutdownDrainTimeoutMillis) {
        this.requestAcceptedWriter = new PhaseWriter(
                "REQUEST_ACCEPTED", jdbcTemplate, transactionOperations, queueCapacity, maxBatchSize,
                maxWaitMillis, enqueueTimeoutMillis, shutdownDrainTimeoutMillis);
        this.startedWriter = new PhaseWriter(
                "STARTED", jdbcTemplate, transactionOperations, queueCapacity, maxBatchSize,
                maxWaitMillis, enqueueTimeoutMillis, shutdownDrainTimeoutMillis);
        this.terminalWriter = new PhaseWriter(
                "TERMINAL", jdbcTemplate, transactionOperations, queueCapacity, maxBatchSize,
                maxWaitMillis, enqueueTimeoutMillis, shutdownDrainTimeoutMillis);
    }

    /**
     * Submits an audit event for asynchronous batch persistence.
     *
     * <p>The returned future completes when the event has been persisted to
     * the database (or has definitively failed). The calling thread should
     * block on this future to enforce Fail Closed semantics.</p>
     *
     * @param event the audit event to persist
     * @return a future that completes when the event is persisted
     */
    public CompletableFuture<Void> submit(AuditEvent event) {
        return writerFor(event).submit(event);
    }

    private PhaseWriter writerFor(AuditEvent event) {
        String eventType = event.eventType();
        if ("REQUEST_ACCEPTED".equals(eventType)) {
            return requestAcceptedWriter;
        } else if ("STARTED".equals(eventType)) {
            return startedWriter;
        } else {
            return terminalWriter;
        }
    }

    @Override
    public void close() {
        log.info("Shutting down AuditBatchWriter, draining remaining events...");
        requestAcceptedWriter.close();
        startedWriter.close();
        terminalWriter.close();
        log.info("AuditBatchWriter shutdown complete");
    }

    /**
     * A single-phase writer with its own queue and commit thread.
     */
    private static final class PhaseWriter {

        private final String name;
        private final JdbcTemplate jdbcTemplate;
        private final TransactionOperations transactionOperations;
        private final int maxBatchSize;
        private final long maxWaitMillis;
        private final long enqueueTimeoutMillis;
        private final long shutdownDrainTimeoutMillis;
        private final BlockingQueue<PendingEvent> queue;
        private final Thread commitThread;
        private volatile boolean shuttingDown = false;

        private record PendingEvent(AuditEvent event, CompletableFuture<Void> future) {
        }

        PhaseWriter(String name, JdbcTemplate jdbcTemplate,
                    TransactionOperations transactionOperations, int queueCapacity,
                    int maxBatchSize, long maxWaitMillis, long enqueueTimeoutMillis,
                    long shutdownDrainTimeoutMillis) {
            this.name = name;
            this.jdbcTemplate = jdbcTemplate;
            this.transactionOperations = transactionOperations;
            this.maxBatchSize = maxBatchSize;
            this.maxWaitMillis = maxWaitMillis;
            this.enqueueTimeoutMillis = enqueueTimeoutMillis;
            this.shutdownDrainTimeoutMillis = shutdownDrainTimeoutMillis;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.commitThread = new Thread(this::runLoop, "audit-batch-" + name.toLowerCase());
            this.commitThread.setDaemon(false);
            this.commitThread.start();
        }

        CompletableFuture<Void> submit(AuditEvent event) {
            if (shuttingDown) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("AuditBatchWriter phase " + name + " is shutting down"));
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            boolean accepted;
            try {
                accepted = queue.offer(new PendingEvent(event, future),
                        enqueueTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(e);
            }
            if (!accepted) {
                log.warn("Audit queue full for phase {}, rejecting event", name);
                future.completeExceptionally(
                        new IllegalStateException("Audit queue full for phase " + name));
            }
            return future;
        }

        private void runLoop() {
            List<PendingEvent> batch = new ArrayList<>(maxBatchSize);
            while (!shuttingDown || !queue.isEmpty()) {
                batch.clear();
                try {
                    PendingEvent first = queue.poll(maxWaitMillis, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }
                    batch.add(first);
                    queue.drainTo(batch, maxBatchSize - 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                flush(batch);
            }
            log.info("AuditBatchWriter phase {} commit thread exited", name);
        }

        private void flush(List<PendingEvent> batch) {
            if (batch.isEmpty()) {
                return;
            }
            try {
                transactionOperations.executeWithoutResult(status -> {
                    jdbcTemplate.batchUpdate(SQL_INSERT_AUDIT_AND_OUTBOX,
                            new BatchPreparedStatementSetter() {
                                @Override
                                public void setValues(PreparedStatement ps, int i) throws SQLException {
                                    AuditEvent event = batch.get(i).event();
                                    bindAuditEvent(ps, event);
                                    ps.setString(16, event.eventType());
                                    JsonbSupport.setJsonb(ps, 17, JsonbSupport.toJson(event));
                                }

                                @Override
                                public int getBatchSize() {
                                    return batch.size();
                                }
                            });
                    persistExecutionRecords(batch);
                });
                for (PendingEvent pe : batch) {
                    pe.future().complete(null);
                }
                if (log.isDebugEnabled()) {
                    log.debug("Audit batch flushed: phase={}, size={}", name, batch.size());
                }
            } catch (DataAccessException ex) {
                log.error("Audit batch insert failed: phase={}, batchSize={}", name, batch.size(), ex);
                for (PendingEvent pe : batch) {
                    pe.future().completeExceptionally(ex);
                }
            }
        }

        private void persistExecutionRecords(List<PendingEvent> batch) {
            List<AuditEvent> started = batch.stream()
                    .map(PendingEvent::event)
                    .filter(event -> "STARTED".equals(event.eventType()))
                    .toList();
            if (!started.isEmpty()) {
                jdbcTemplate.batchUpdate(SQL_INSERT_EXECUTION_RECORD,
                        new BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(PreparedStatement ps, int i) throws SQLException {
                                AuditEvent event = started.get(i);
                                ps.setString(1, event.requestId());
                                ps.setString(2, event.subjectDigest() != null
                                        ? event.subjectDigest() : "unknown");
                                ps.setLong(3, event.orgId());
                                ps.setString(4, event.capabilityId());
                                ps.setString(5, event.capabilityVersion());
                                ps.setLong(6, event.snapshotVersion());
                                JsonbSupport.setJsonb(ps, 7,
                                        event.detailsJson() != null ? event.detailsJson() : "{}");
                            }

                            @Override
                            public int getBatchSize() {
                                return started.size();
                            }
                        });
            }

            List<AuditEvent> terminal = batch.stream()
                    .map(PendingEvent::event)
                    .filter(event -> "TERMINAL".equals(event.eventType()))
                    .toList();
            if (!terminal.isEmpty()) {
                jdbcTemplate.batchUpdate(SQL_UPDATE_EXECUTION_RECORD,
                        new BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(PreparedStatement ps, int i) throws SQLException {
                                AuditEvent event = terminal.get(i);
                                ps.setString(1, event.resultCode() != null
                                        ? event.resultCode() : "TERMINAL");
                                ps.setString(2, event.requestId());
                            }

                            @Override
                            public int getBatchSize() {
                                return terminal.size();
                            }
                        });
            }
        }

        private static void bindAuditEvent(PreparedStatement ps, AuditEvent event) throws SQLException {
            ps.setString(1, event.eventType());
            ps.setTimestamp(2, Timestamp.from(event.timestamp()));
            setNullableString(ps, 3, event.subjectDigest());
            ps.setLong(4, event.orgId());
            setNullableString(ps, 5, event.requestId());
            setNullableString(ps, 6, event.operationId());
            setNullableString(ps, 7, event.capabilityId());
            setNullableString(ps, 8, event.capabilityVersion());
            setNullableString(ps, 9, event.manifestDigest());
            ps.setLong(10, event.snapshotVersion());
            setNullableString(ps, 11, event.policyDecisionId());
            setNullableString(ps, 12, event.modelPromptVersion());
            setNullableString(ps, 13, event.resultCode());
            ps.setLong(14, event.durationMs());
            JsonbSupport.setJsonb(ps, 15, event.detailsJson());
        }

        private static void setNullableString(PreparedStatement ps, int index, String value)
                throws SQLException {
            if (value != null) {
                ps.setString(index, value);
            } else {
                ps.setNull(index, Types.VARCHAR);
            }
        }

        void close() {
            shuttingDown = true;
            try {
                commitThread.join(shutdownDrainTimeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (commitThread.isAlive()) {
                log.warn("AuditBatchWriter phase {} did not drain within {}ms, failing remaining events",
                        name, shutdownDrainTimeoutMillis);
                List<PendingEvent> remaining = new ArrayList<>();
                queue.drainTo(remaining);
                for (PendingEvent pe : remaining) {
                    pe.future().completeExceptionally(
                            new IllegalStateException("AuditBatchWriter shutdown before drain completed"));
                }
            }
        }
    }
}
