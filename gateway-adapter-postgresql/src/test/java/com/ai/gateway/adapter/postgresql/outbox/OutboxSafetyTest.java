package com.ai.gateway.adapter.postgresql.outbox;

import com.ai.gateway.domain.port.OutboxPort.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxSafetyTest {

    @Test
    void defaultRelayDoesNotTreatLoggingAsSuccessfulExport() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("COUNT"), org.mockito.ArgumentMatchers.<Class<Long>>any()))
                .thenReturn(1L);
        when(jdbc.query(contains("outbox_event"), org.mockito.ArgumentMatchers.<RowMapper<OutboxEvent>>any(), anyInt()))
                .thenReturn(List.of(new OutboxEvent(9L, "STARTED", "{}", Instant.now())));

        new OutboxRelay(jdbc).relay();

        verify(jdbc, never()).update(contains("EXPORTED"), any(Object[].class));
    }

    @Test
    void retentionSqlRequiresSuccessfullyExportedOutboxRecord() throws Exception {
        var field = DataRetentionScheduler.class.getDeclaredField("SQL_DELETE_AGED_AUDIT_EVENTS");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        assertThat(sql).contains("outbox_event").contains("EXPORTED").contains("audit_event_id");
    }
}
