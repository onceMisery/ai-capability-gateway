package com.ai.gateway.adapter.postgresql.outbox;

import com.ai.gateway.domain.port.OutboxPort.OutboxEvent;
import com.ai.gateway.domain.port.OutboxExporterPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Outbox 与审计保留的安全语义：默认中继器不会把日志当成成功的导出，保留 SQL 要求已成功
 * 导出的 outbox 记录，失败的导出保持待处理并在重试时成功。
 *
 * @author cmiracle@163.com
 */
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

    @Test
    void failedExportRemainsPendingAndIsRetried() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboxExporterPort exporter = mock(OutboxExporterPort.class);
        OutboxEvent event = new OutboxEvent(9L, "STARTED", "{}", Instant.now());
        when(jdbc.queryForObject(contains("COUNT"), org.mockito.ArgumentMatchers.<Class<Long>>any()))
                .thenReturn(1L);
        when(jdbc.query(contains("outbox_event"),
                org.mockito.ArgumentMatchers.<RowMapper<OutboxEvent>>any(), anyInt()))
                .thenReturn(List.of(event));
        org.mockito.Mockito.doThrow(new IllegalStateException("sink unavailable"))
                .doNothing()
                .when(exporter).export(event);

        OutboxRelay relay = new OutboxRelay(jdbc, exporter, 10, 100);
        relay.relay();
        verify(jdbc, never()).update(contains("EXPORTED"), eq(9L));

        relay.relay();
        verify(exporter, times(2)).export(event);
        verify(jdbc, times(1)).update(contains("EXPORTED"), eq(9L));
    }
}
