package com.ai.gateway.adapter.postgresql.audit;

import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.ExecutionAuditContext;
import com.ai.gateway.domain.port.AuditPort;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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
 * {@link AuditPort} 的 JDBC 实现，支持微批处理。
 *
 * <p>所有终态都必须以 Fail Closed 语义进行审计：若审计持久化失败，网关拒绝继续处理。
 * 在调用 Provider 之前必须持久化一个 STARTED 事件；调用返回数据给客户端之前，必须持久化
 * 终态。</p>
 *
 * <p>本实现将写入委托给 {@link AuditBatchWriter} 进行微批处理：多个并发请求共享同一次
 * 数据库事务与网络往返，在降低单次调用 IO 成本的同时保持“调用线程阻塞直到持久化完成”的语义。</p>
 *
 * <p>阻塞超时时间可配置（默认 10 秒）。若审计事件未能在超时时间内持久化，则抛出
 * {@link RuntimeException} 以强制执行 Fail Closed 行为。</p>
 *
 * @author cmiracle@163.com
 * @see AuditPort
 * @see AuditBatchWriter
 * @since 0.1.0
 */
@Slf4j
@Component
public class JdbcAuditPort implements AuditPort {

    private static final long DEFAULT_AUDIT_TIMEOUT_MILLIS = 10_000L;

    private final AuditBatchWriter batchWriter;
    private final long auditTimeoutMillis;

    /**
     * 使用默认配置构造一个新的 JdbcAuditPort。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     */
    @Autowired
    public JdbcAuditPort(JdbcTemplate jdbcTemplate,
                         PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, transactionManager, DEFAULT_AUDIT_TIMEOUT_MILLIS);
    }

    /**
     * 使用自定义审计超时构造一个新的 JdbcAuditPort。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     * @param auditTimeoutMillis 等待审计持久化的最长时间
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
    public void recordStarted(ExecutionAuditContext context) {
        Objects.requireNonNull(context, "context must not be null");

        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                "STARTED",
                Instant.now(),
                context.subjectDigest(),
                context.orgId(),
                context.requestId(),
                context.operationId(),
                context.capabilityId(),
                context.capabilityVersion(),
                context.manifestDigest(),
                context.snapshotVersion(), null, null, null, 0L, null);
        blockUntilPersisted(batchWriter.submit(event));
    }

    @Override
    public void recordTerminal(ExecutionAuditContext context, String resultCode,
                               long durationMs, String detailsJson) {
        Objects.requireNonNull(context, "context must not be null");

        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                "TERMINAL",
                Instant.now(),
                context.subjectDigest(),
                context.orgId(),
                context.requestId(),
                context.operationId(),
                context.capabilityId(),
                context.capabilityVersion(),
                context.manifestDigest(),
                context.snapshotVersion(), null, null, resultCode, durationMs, detailsJson);
        blockUntilPersisted(batchWriter.submit(event));
    }

    @Override
    public void recordEvent(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        blockUntilPersisted(batchWriter.submit(event));
    }

    /**
     * 阻塞调用线程，直到审计事件被持久化或失败。
     *
     * <p>Fail Closed：若持久化失败或超时，则抛出 {@link RuntimeException}，使调用方无法在没有
     * 审计记录的情况下继续处理。</p>
     *
     * @param future 表示持久化操作的 future
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
     * 优雅关闭批量写入器，排空剩余事件。
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down JdbcAuditPort...");
        batchWriter.close();
        log.info("JdbcAuditPort shutdown complete");
    }
}
