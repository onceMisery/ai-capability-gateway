package com.ai.gateway.adapter.postgresql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationV5Test {

    @Test
    void v5AddsAuditOutboxCorrelationAndExecutionIndexes() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/V5__control_plane_consistency.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("audit_event_id").contains("execution_record");
        }
    }
}
