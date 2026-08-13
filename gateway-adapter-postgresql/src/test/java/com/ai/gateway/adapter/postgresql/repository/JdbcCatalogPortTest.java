package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcCatalogPortTest {

    @Test
    void catalogPortExposesDatabaseBackedSnapshotVersionReservation() throws Exception {
        assertThat(CatalogPort.class.getMethod("reserveSnapshotVersion").getReturnType())
                .isEqualTo(long.class);
    }

    @Test
    void saveSnapshotIsTransactionalAndPersistsStoredManifestDigest() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CapabilityManifest manifest = mock(CapabilityManifest.class);
        CapabilityManifest.Metadata metadata = mock(CapabilityManifest.Metadata.class);
        when(manifest.metadata()).thenReturn(metadata);
        when(metadata.id()).thenReturn("order.detail.query");
        when(metadata.version()).thenReturn("1.0.0");
        when(jdbc.queryForObject(contains("sha256_digest"), eq(String.class),
                eq("order.detail.query"), eq("1.0.0"))).thenReturn("abc123");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        JdbcCatalogPort port = new JdbcCatalogPort(jdbc);
        port.saveSnapshot(new CatalogSnapshot(7L, "production", List.of(manifest), "policy-v1", "snapshot-digest"));

        assertThat(JdbcCatalogPort.class.getMethod("saveSnapshot", CatalogSnapshot.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        verify(jdbc).update(contains("catalog_snapshot_item"),
                eq(7L), eq("order.detail.query"), eq("1.0.0"), eq("abc123"), eq("policy-v1"));
        verify(jdbc).update(contains("audit_event"), any(Object[].class));
    }
}
