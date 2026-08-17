package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcOperationRepositoryIdempotencyTest {

    @Test
    void returnsExistingOperationWhenIdempotencyKeyAlreadyExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcOperationRepository repository = new JdbcOperationRepository(jdbc);
        OperationRecord incoming = record("00000000-0000-0000-0000-000000000001", "idem-1");
        OperationRecord existing = record("00000000-0000-0000-0000-000000000002", "idem-1");

        when(jdbc.update(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class)))
                .thenReturn(0);
        when(jdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<OperationRecord>>any(), eq("idem-1")))
                .thenReturn(List.of(existing));

        OperationRecord persisted = repository.saveOrGetByIdempotencyKey(incoming);

        assertThat(persisted.operationId()).isEqualTo(existing.operationId());
        verify(jdbc).query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<OperationRecord>>any(), eq("idem-1"));
    }

    private static OperationRecord record(String operationId, String idempotencyKey) {
        return new OperationRecord(operationId, OperationState.PREPARED, "principal", 7L,
                "order.create", "1.0.0", "manifest", 11L, "ciphertext",
                "arguments", idempotencyKey, "policy", null,
                Instant.now().plusSeconds(300), 0L);
    }
}
