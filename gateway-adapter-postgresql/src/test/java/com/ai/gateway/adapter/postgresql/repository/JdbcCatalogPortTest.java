package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.service.ManifestDigest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcCatalogPort} 的单元测试，验证数据库支持的快照版本预留、事务性保存以及发布事件
 * 与快照持久化相互独立。
 *
 * @author cmiracle@163.com
 */
class JdbcCatalogPortTest {

    @Test
    void catalogReadBudgetRejectsInvalidLimits() {
        assertThatThrownBy(() -> new CatalogReadBudget(0, 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogReadBudget(1, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogReadBudget(1, 1, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentSnapshotQueryAppliesJdbcReadLimits() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        java.sql.PreparedStatement statement = mock(java.sql.PreparedStatement.class);
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    PreparedStatementSetter setter = invocation.getArgument(1);
                    setter.setValues(statement);
                    return List.of();
                });

        JdbcCatalogPort port = new JdbcCatalogPort(jdbc,
                new CatalogReadBudget(17, 4, 1024L));

        port.loadCurrentSnapshot("production");

        verify(statement).setMaxRows(17);
        verify(statement).setQueryTimeout(4);
    }

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
        String digest = ManifestDigest.sha256(manifest);
        when(jdbc.queryForObject(contains("sha256_digest"), eq(String.class),
                eq("order.detail.query"), eq("1.0.0"))).thenReturn(digest);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        JdbcCatalogPort port = new JdbcCatalogPort(jdbc);
        port.saveSnapshot(new CatalogSnapshot(7L, "production", List.of(manifest), "policy-v1", "snapshot-digest"));

        assertThat(JdbcCatalogPort.class.getMethod("saveSnapshot", CatalogSnapshot.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        verify(jdbc).queryForObject(contains("pg_advisory_xact_lock"),
                eq(Integer.class), eq("production"));
        verify(jdbc).update(contains("catalog_snapshot_item"),
                eq(7L), eq("order.detail.query"), eq("1.0.0"), eq(digest), eq("policy-v1"));
        verify(jdbc, org.mockito.Mockito.never()).update(contains("audit_event"), any(Object[].class));
    }

    @Test
    void publicationEventIsExplicitAndSeparateFromSnapshotPersistence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcCatalogPort port = new JdbcCatalogPort(jdbc);
        CatalogSnapshot snapshot = new CatalogSnapshot(7L, "production", List.of(), "policy-v1", "digest");

        port.recordSnapshotPublication(snapshot, "MANIFEST_PUBLISHED");

        verify(jdbc).update(contains("audit_event"), any(Object[].class));
        verify(jdbc).update(contains("ON CONFLICT DO NOTHING"), any(Object[].class));
    }
}
