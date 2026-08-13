package com.ai.gateway.adapter.postgresql.audit;

import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.port.AuditPort;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JDBC implementation of {@link AuditPort} with micro-batching support
 *
 * <p>All terminal states must be audited with Fail Closed semantics: if audit
 * persistence fails, the gateway refuses to continue processing. Before
 * calling the Provider, a STARTED event must be persisted; after the call,
 * the terminal state must be persisted before returning data to the client.</p>
 *
 * <p>This implementation delegates to {@link AuditBatchWriter} for
 * micro-batching: multiple concurrent requests share the same database
 * transaction and network round-trip, reducing the per-call IO cost while
 * maintaining the "calling thread blocks until persisted" semantic.</p>
 *
 * <p>The blocking timeout is configurable (default 10 seconds). If the audit
 * event is not persisted within the timeout, a {@link RuntimeException} is
 * thrown to enforce Fail Closed behavior.</p>
 *
 * @see AuditPort
 * @see AuditBatchWriter
 * @since 0.1.0
 */
@Component
public class JdbcAuditPort implements AuditPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditPort.class);

    private static final long DEFAULT_AUDIT_TIMEOUT_MILLIS = 10_000L;

    private final AuditBatchWriter batchWriter;
    private final long auditTimeoutMillis;

    /**
     * Constructs a new JdbcAuditPort with default configuration.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     */
    @Autowired
    public JdbcAuditPort(JdbcTemplate jdbcTemplate,
                         PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, transactionManager, DEFAULT_AUDIT_TIMEOUT_MILLIS);
    }

    /**
     * Constructs a new JdbcAuditPort with a custom audit timeout.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     * @param auditTimeoutMillis the maximum time to wait for audit persistence
     */
    public JdbcAuditPort(JdbcTemplate jdbcTemplate,
                         PlatformTransactionManager transactionManager,
                         long auditTimeoutMillis) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.batchWriter = new AuditBatchWriter(jdbcTemplate,
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.auditTimeoutMillis = auditTimeoutMillis;
    }

    @Override
    public void recordAccepted(String requestId, String subjectDigest, long orgId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(subjectDigest, "subjectDigest must not be null");

        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                "REQUEST_ACCEPTED",
                Instant.now(),
                subjectDigest,
                orgId,
                requestId,
                null, null, null, null,
                0L, null, null, null, 0L, null);
        blockUntilPersisted(batchWriter.submit(event));
    }

    @Override
    public void recordStarted(String requestId, String capabilityId, String capabilityVersion,
                              String manifestDigest) {
        Objects.requireNonNull(requestId, "requestId must not be null");

        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                "STARTED",
                Instant.now(),
                null,
                0L,
                requestId,
                null,
                capabilityId,
                capabilityVersion,
                manifestDigest,
                0L, null, null, null, 0L, null);
        blockUntilPersisted(batchWriter.submit(event));
    }

    @Override
    public void recordTerminal(String requestId, String capabilityId, String capabilityVersion,
                               String resultCode, long durationMs, String detailsJson) {
        Objects.requireNonNull(requestId, "requestId must not be null");

        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                "TERMINAL",
                Instant.now(),
                null,
                0L,
                requestId,
                null,
                capabilityId,
                capabilityVersion,
                null,
                0L, null, null, resultCode, durationMs, detailsJson);
        blockUntilPersisted(batchWriter.submit(event));
    }

    @Override
    public void recordEvent(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        blockUntilPersisted(batchWriter.submit(event));
    }

    /**
     * Blocks the calling thread until the audit event is persisted or fails.
     *
     * <p>Fail Closed: if persistence fails or times out, a
     * {@link RuntimeException} is thrown so the caller cannot continue
     * processing without the audit record.</p>
     *
     * @param future the future representing the persistence operation
     */
    private void blockUntilPersisted(CompletableFuture<Void> future) {
        try {
            future.get(auditTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Audit persistence timed out after " + auditTimeoutMillis + "ms (Fail Closed)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Audit persistence interrupted (Fail Closed)", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Audit persistence failed (Fail Closed)", cause);
        }
    }

    /**
     * Gracefully shuts down the batch writer, draining remaining events.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down JdbcAuditPort...");
        batchWriter.close();
        log.info("JdbcAuditPort shutdown complete");
    }
}
