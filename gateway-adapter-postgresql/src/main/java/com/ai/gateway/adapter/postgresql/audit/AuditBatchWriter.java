package com.ai.gateway.adapter.postgresql.audit;

import com.ai.gateway.adapter.postgresql.JsonbSupport;
import com.ai.gateway.domain.model.AuditEvent;
import lombok.extern.slf4j.Slf4j;
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
 * 微批处理审计写入器，实现应用层的组提交（group-commit）模式。
 *
 * <p>不改变“调用线程必须等待其审计事件被持久化”的同步语义，而是通过把多个并发
 * 请求的审计插入批量合并，共享同一次数据库事务与网络往返，从而降低单次调用的 IO 成本。</p>
 *
 * <p>关键设计决策：</p>
 * <ul>
 * <li><b>事件队列：</b> 使用有界容量的 {@link ArrayBlockingQueue}。队列满时新事件被拒绝，
 * 以防止无界增长。</li>
 * <li><b>提交线程：</b> 每种事件类型（REQUEST_ACCEPTED、STARTED、TERMINAL）各有一个
 * 专属线程，避免跨阶段相互阻塞。</li>
 * <li><b>批量触发：</b> 达到 {@code maxBatchSize}（默认 50）或 {@code maxWaitMillis}
 * （默认 5ms）二者之一即触发刷新。</li>
 * <li><b>批量写入：</b> 在单个事务中执行 {@code PreparedStatement.addBatch()} +
 * {@code executeBatch()}。</li>
 * <li><b>调用方释放：</b> 每个事件携带一个 {@link CompletableFuture}，在批次提交后完成；
 * 调用线程会阻塞直到该 future 完成。</li>
 * <li><b>持久性：</b> {@code synchronous_commit = on}（PostgreSQL 默认值），不降级持久性。</li>
 * </ul>
 *
 * <p>崩溃安全性：如果进程崩溃时提交线程正处于某个批次中间，未提交的事件会丢失，但对应的
 * 调用线程也会随之终止（不会向客户端返回响应），因此不会出现静默不一致——与单行同步写入
 * 行为一致。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
public class AuditBatchWriter implements AutoCloseable {

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
     * 使用默认配置构造一个新的 AuditBatchWriter。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
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
     * 使用自定义配置构造一个新的 AuditBatchWriter。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     * @param queueCapacity 每个阶段的队列容量上限
     * @param maxBatchSize 每个批次的最大事件数
     * @param maxWaitMillis 刷新部分批次前的最长等待时间
     * @param enqueueTimeoutMillis 队列满时入队的超时时间
     * @param shutdownDrainTimeoutMillis 关闭时排空队列的超时时间
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
     * 提交一个审计事件以进行异步批量持久化。
     *
     * <p>返回的 future 在事件已持久化到数据库（或已明确失败）时完成。调用线程应阻塞
     * 该 future 以强制执行 Fail Closed 语义。</p>
     *
     * @param event 待持久化的审计事件
     * @return 事件被持久化后完成的 future
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
     * 单阶段写入器，拥有独立的队列与提交线程。
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
