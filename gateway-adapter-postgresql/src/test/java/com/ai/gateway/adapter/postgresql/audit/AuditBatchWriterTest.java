package com.ai.gateway.adapter.postgresql.audit;

import com.ai.gateway.domain.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link AuditBatchWriter} 的单元测试，验证审计持久化同时写入 outbox 与 execution_record。
 *
 * @author cmiracle@163.com
 */
class AuditBatchWriterTest {

    @Test
    void auditPersistenceAlsoWritesOutboxAndExecutionRecord() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditBatchWriter writer = new AuditBatchWriter(jdbc, 10, 10, 1, 50, 1000);
        try {
            AuditEvent event = new AuditEvent(
                    "event-1", "STARTED", Instant.now(), "subject-digest", 42L,
                    "request-1", null, "order.detail.query", "1.0.0", "manifest-digest",
                    11L, null, null, null, 0L, "{}");

            writer.submit(event).get(1, TimeUnit.SECONDS);

            verify(jdbc).batchUpdate(contains("audit_event"), any(BatchPreparedStatementSetter.class));
            verify(jdbc).batchUpdate(contains("outbox_event"), any(BatchPreparedStatementSetter.class));
            ArgumentCaptor<BatchPreparedStatementSetter> executionSetter =
                    ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
            verify(jdbc).batchUpdate(contains("execution_record"), executionSetter.capture());

            PreparedStatement statement = mock(PreparedStatement.class);
            executionSetter.getValue().setValues(statement, 0);
            verify(statement).setString(1, "request-1");
            verify(statement).setString(2, "subject-digest");
            verify(statement).setLong(3, 42L);
            verify(statement).setLong(6, 11L);
        } finally {
            writer.close();
        }
    }
}
