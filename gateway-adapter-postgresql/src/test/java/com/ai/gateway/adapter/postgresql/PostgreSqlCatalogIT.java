package com.ai.gateway.adapter.postgresql;

import com.ai.gateway.adapter.postgresql.repository.JdbcCatalogPort;
import com.ai.gateway.adapter.postgresql.repository.JdbcManifestRepository;
import com.ai.gateway.adapter.postgresql.outbox.OutboxRelay;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;
import com.ai.gateway.domain.service.ManifestDigest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlCatalogIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrateSingleBaseline() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE TABLE capability_manifest, catalog_snapshot, audit_event "
                + "RESTART IDENTITY CASCADE");
    }

    @Test
    void publishesLoadsAndRecordsAuditOutboxFromTheSingleBaseline() {
        CapabilityManifest manifest = manifest("order.query", "1.0.0", "query order");
        JdbcManifestRepository manifests = new JdbcManifestRepository(jdbc);
        JdbcCatalogPort catalog = new JdbcCatalogPort(jdbc);
        manifests.save(manifest, ManifestDigest.sha256(manifest));
        jdbc.update("UPDATE capability_manifest SET lifecycle = 'APPROVED' WHERE id = ? AND version = ?",
                manifest.metadata().id(), manifest.metadata().version());

        long version = catalog.reserveSnapshotVersion();
        String policyRef = "policy-v" + version;
        CatalogSnapshot snapshot = new CatalogSnapshot(version, "production", List.of(manifest),
                policyRef, CatalogSnapshotDigest.sha256(version, "production", policyRef, List.of(manifest)));
        transactions.executeWithoutResult(status -> {
            catalog.saveSnapshot(snapshot);
            manifests.updateLifecycle(manifest.metadata().id(), manifest.metadata().version(),
                    com.ai.gateway.domain.model.CapabilityLifecycle.PUBLISHED);
            catalog.recordSnapshotPublication(snapshot, "MANIFEST_PUBLISHED");
        });

        CatalogSnapshot loaded = catalog.loadCurrentSnapshot("production");
        assertThat(loaded).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM capability_manifest WHERE id = ?",
                String.class, manifest.metadata().id())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type = 'MANIFEST_PUBLISHED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'MANIFEST_PUBLISHED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND is_identity = 'YES'", Integer.class)).isEqualTo(14);
    }

    @Test
    void rollsBackSnapshotLifecycleAuditAndOutboxTogether() {
        CapabilityManifest manifest = manifest("order.query", "1.0.0", "query order");
        JdbcManifestRepository manifests = new JdbcManifestRepository(jdbc);
        JdbcCatalogPort catalog = new JdbcCatalogPort(jdbc);
        manifests.save(manifest, ManifestDigest.sha256(manifest));
        jdbc.update("UPDATE capability_manifest SET lifecycle = 'APPROVED' WHERE id = ? AND version = ?",
                manifest.metadata().id(), manifest.metadata().version());
        int auditBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class);
        int outboxBefore = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
        long version = catalog.reserveSnapshotVersion();
        CatalogSnapshot snapshot = new CatalogSnapshot(version, "production", List.of(manifest),
                "policy", CatalogSnapshotDigest.sha256(version, "production", "policy", List.of(manifest)));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            catalog.saveSnapshot(snapshot);
            manifests.updateLifecycle(manifest.metadata().id(), manifest.metadata().version(),
                    com.ai.gateway.domain.model.CapabilityLifecycle.PUBLISHED);
            catalog.recordSnapshotPublication(snapshot, "MANIFEST_PUBLISHED");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_snapshot", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM capability_manifest WHERE id = ?",
                String.class, manifest.metadata().id())).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(outboxBefore);
    }

    @Test
    void failsClosedWhenStoredManifestDigestIsTampered() {
        CapabilityManifest manifest = manifest("order.query", "1.0.0", "query order");
        JdbcManifestRepository manifests = new JdbcManifestRepository(jdbc);
        JdbcCatalogPort catalog = new JdbcCatalogPort(jdbc);
        manifests.save(manifest, ManifestDigest.sha256(manifest));
        long version = catalog.reserveSnapshotVersion();
        CatalogSnapshot snapshot = new CatalogSnapshot(version, "production", List.of(manifest),
                "policy", CatalogSnapshotDigest.sha256(version, "production", "policy", List.of(manifest)));
        catalog.saveSnapshot(snapshot);
        jdbc.update("UPDATE catalog_snapshot_item SET manifest_digest = repeat('0', 64) "
                + "WHERE snapshot_version = ?", version);

        assertThatThrownBy(() -> catalog.loadSnapshot(version))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Manifest digest verification failed");
    }

    @Test
    void serializesConcurrentPublicationsAndKeepsExactlyOneActiveSnapshot() throws Exception {
        CapabilityManifest manifest = manifest("order.concurrent", "1.0.0", "query order concurrently");
        JdbcManifestRepository manifests = new JdbcManifestRepository(jdbc);
        JdbcCatalogPort catalog = new JdbcCatalogPort(jdbc);
        manifests.save(manifest, ManifestDigest.sha256(manifest));

        CatalogSnapshot first = snapshot(catalog.reserveSnapshotVersion(), manifest, "production", "policy-a");
        CatalogSnapshot second = snapshot(catalog.reserveSnapshotVersion(), manifest, "production", "policy-b");
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);

        CompletableFuture<Void> firstPublish = CompletableFuture.runAsync(() -> publishAfter(start, catalog, first));
        CompletableFuture<Void> secondPublish = CompletableFuture.runAsync(() -> publishAfter(start, catalog, second));
        start.countDown();
        CompletableFuture.allOf(firstPublish, secondPublish).get(15, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_snapshot", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_snapshot "
                + "WHERE environment = 'production' AND status = 'ACTIVE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateSnapshotPublicationEventIsIdempotent() {
        CapabilityManifest manifest = manifest("order.idempotent", "1.0.0", "query order idempotently");
        JdbcManifestRepository manifests = new JdbcManifestRepository(jdbc);
        JdbcCatalogPort catalog = new JdbcCatalogPort(jdbc);
        manifests.save(manifest, ManifestDigest.sha256(manifest));
        CatalogSnapshot snapshot = snapshot(
                catalog.reserveSnapshotVersion(), manifest, "production", "policy-idempotent");
        transactions.executeWithoutResult(status -> {
            catalog.saveSnapshot(snapshot);
            catalog.recordSnapshotPublication(snapshot, "MANIFEST_PUBLISHED");
            catalog.recordSnapshotPublication(snapshot, "MANIFEST_PUBLISHED");
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE snapshot_version = ?",
                Integer.class, snapshot.snapshotVersion())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'MANIFEST_PUBLISHED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void failedOutboxExportRemainsPendingAndSucceedsOnRetry() {
        jdbc.update("INSERT INTO outbox_event(event_type, payload) VALUES ('TEST_EVENT', '{}'::jsonb)");
        AtomicInteger attempts = new AtomicInteger();
        OutboxRelay relay = new OutboxRelay(jdbc, event -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("sink unavailable");
            }
        }, 10, 100);

        relay.relay();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_type = 'TEST_EVENT'",
                String.class)).isEqualTo("PENDING");
        relay.relay();
        assertThat(attempts).hasValue(2);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_type = 'TEST_EVENT'",
                String.class)).isEqualTo("EXPORTED");
    }

    @Test
    void identityAuditWriteBaseline() {
        int rows = 1_000;
        long started = System.nanoTime();
        List<Object[]> arguments = java.util.stream.IntStream.range(0, rows)
                .mapToObj(index -> new Object[0])
                .toList();
        jdbc.batchUpdate("INSERT INTO audit_event(event_type, details) VALUES ('PERF_IDENTITY', '{}'::jsonb)",
                arguments);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type = 'PERF_IDENTITY'",
                Integer.class)).isEqualTo(rows);
        System.out.printf("PERF_BASELINE postgresIdentityRows=%d elapsedMs=%d%n", rows, elapsedMs);
        assertThat(elapsedMs).isLessThan(30_000L);
    }

    private static void publishAfter(java.util.concurrent.CountDownLatch start,
                                     JdbcCatalogPort catalog,
                                     CatalogSnapshot snapshot) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent publication start timed out");
            }
            transactions.executeWithoutResult(status -> catalog.saveSnapshot(snapshot));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent publication interrupted", ex);
        }
    }

    private static CatalogSnapshot snapshot(long version, CapabilityManifest manifest,
                                            String environment, String policyRef) {
        return new CatalogSnapshot(version, environment, List.of(manifest), policyRef,
                CatalogSnapshotDigest.sha256(version, environment, policyRef, List.of(manifest)));
    }

    private static CapabilityManifest manifest(String id, String version, String description) {
        return new CapabilityManifest("gateway.ai/v1", "Capability",
                new CapabilityManifest.Metadata(id, version,
                        new CapabilityManifest.Owner("team", "team@example.com"), List.of("order")),
                new CapabilityManifest.Spec(description, description,
                        new CapabilityManifest.Examples(List.of(description), List.of("other"), List.of("order")),
                        RiskLevel.READ_ONLY, Map.of("type", "object"),
                        new CapabilityManifest.Authorization(List.of(), Map.of()),
                        new ProtocolBinding(Protocol.DUBBO, "nacos-main", "example.OrderService",
                                null, null, "query", List.of(), "hessian2", List.of(), Map.of()),
                        new OutputContract(OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 1024),
                        new ResiliencePolicy(1000, 0, 8, true)));
    }
}
