package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.adapter.postgresql.JsonbSupport;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.SnapshotSummary;
import com.ai.gateway.domain.port.CatalogPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;

/**
 * JDBC implementation of {@link CatalogPort} backed by PostgreSQL.
 *
 * <p>Loads catalog snapshots from the {@code catalog_snapshot} and
 * {@code catalog_snapshot_item} tables. Each snapshot is
 * immutable once published; rollback copies a historical snapshot's content
 * into a new snapshot version.</p>
 *
 * <p>The current active snapshot is identified by {@code status = 'ACTIVE'}.
 * Snapshot items reference capability manifests by ID and version; the full
 * manifest content is loaded from {@code capability_manifest} to reconstruct
 * the {@link CatalogSnapshot}.</p>
 *
 * @see CatalogPort
 * @since 0.1.0
 */
@Repository
@Qualifier("postgresCatalogPort")
public class JdbcCatalogPort implements CatalogPort {

    private static final String SQL_FIND_ACTIVE_SNAPSHOT =
            "SELECT snapshot_version, environment, digest FROM catalog_snapshot " +
            "WHERE environment = ? AND status = 'ACTIVE' ORDER BY snapshot_version DESC LIMIT 1";

    private static final String SQL_FIND_SNAPSHOT_BY_VERSION =
            "SELECT snapshot_version, environment, digest FROM catalog_snapshot " +
            "WHERE snapshot_version = ?";

    private static final String SQL_FIND_SNAPSHOT_ITEMS =
            "SELECT capability_id, capability_version, policy_ref " +
            "FROM catalog_snapshot_item WHERE snapshot_version = ?";

    private static final String SQL_FIND_MANIFEST =
            "SELECT raw_content FROM capability_manifest WHERE id = ? AND version = ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs a new JdbcCatalogPort.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     */
    public JdbcCatalogPort(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public long reserveSnapshotVersion() {
        Long version = jdbcTemplate.queryForObject(
                "SELECT nextval('catalog_snapshot_snapshot_version_seq')", Long.class);
        if (version == null) {
            throw new IllegalStateException("PostgreSQL did not allocate a snapshot version");
        }
        return version;
    }

    @Override
    public CatalogSnapshot loadCurrentSnapshot(String environment) {
        Objects.requireNonNull(environment, "environment must not be null");

        List<SnapshotRow> snapshots = jdbcTemplate.query(
                SQL_FIND_ACTIVE_SNAPSHOT,
                snapshotRowMapper(),
                environment);
        if (snapshots.isEmpty()) {
            return new CatalogSnapshot(0L, environment, List.of(), null, "");
        }
        return loadSnapshotInternal(snapshots.get(0));
    }

    @Override
    public CatalogSnapshot loadSnapshot(long snapshotVersion) {
        List<SnapshotRow> snapshots = jdbcTemplate.query(
                SQL_FIND_SNAPSHOT_BY_VERSION,
                snapshotRowMapper(),
                snapshotVersion);
        if (snapshots.isEmpty()) {
            throw new IllegalStateException("Snapshot not found: " + snapshotVersion);
        }
        return loadSnapshotInternal(snapshots.get(0));
    }

    @Override
    public Optional<CapabilityManifest> findCapability(String capabilityId, String version) {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(version, "version must not be null");

        List<CapabilityManifest> results = jdbcTemplate.query(
                SQL_FIND_MANIFEST,
                manifestRowMapper(),
                capabilityId, version);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private CatalogSnapshot loadSnapshotInternal(SnapshotRow snapshot) {
        List<ItemRow> items = jdbcTemplate.query(
                SQL_FIND_SNAPSHOT_ITEMS,
                itemRowMapper(),
                snapshot.snapshotVersion());

        List<CapabilityManifest> capabilities = new ArrayList<>(items.size());
        String policyRef = null;

        for (ItemRow item : items) {
            if (policyRef == null && item.policyRef() != null) {
                policyRef = item.policyRef();
            }
            List<CapabilityManifest> manifests = jdbcTemplate.query(
                    SQL_FIND_MANIFEST,
                    manifestRowMapper(),
                    item.capabilityId(), item.capabilityVersion());
            if (!manifests.isEmpty()) {
                capabilities.add(manifests.get(0));
            }
        }

        return new CatalogSnapshot(
                snapshot.snapshotVersion(),
                snapshot.environment(),
                List.copyOf(capabilities),
                policyRef,
                snapshot.digest());
    }

    private static RowMapper<SnapshotRow> snapshotRowMapper() {
        return (rs, rowNum) -> new SnapshotRow(
                rs.getLong("snapshot_version"),
                rs.getString("environment"),
                rs.getString("digest"));
    }

    private static RowMapper<ItemRow> itemRowMapper() {
        return (rs, rowNum) -> new ItemRow(
                rs.getString("capability_id"),
                rs.getString("capability_version"),
                rs.getString("policy_ref"));
    }

    private static RowMapper<CapabilityManifest> manifestRowMapper() {
        return (rs, rowNum) -> {
            String rawContent = rs.getString("raw_content");
            return JsonbSupport.fromJson(rawContent, CapabilityManifest.class);
        };
    }

    private record SnapshotRow(long snapshotVersion, String environment, String digest) {
    }

    private record ItemRow(String capabilityId, String capabilityVersion, String policyRef) {
    }

    @Override
    @Transactional
    public void saveSnapshot(CatalogSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // Mark previous ACTIVE snapshots as SUPERSEDED
        jdbcTemplate.update(
                "UPDATE catalog_snapshot SET status = 'SUPERSEDED' WHERE environment = ? AND status = 'ACTIVE'",
                snapshot.environment());

        // Insert the new snapshot
        jdbcTemplate.update(
                "INSERT INTO catalog_snapshot (snapshot_version, environment, status, digest) VALUES (?, ?, 'ACTIVE', ?)",
                snapshot.snapshotVersion(), snapshot.environment(),
                snapshot.digest() != null ? snapshot.digest() : "");

        // Insert snapshot items
        for (CapabilityManifest manifest : snapshot.capabilities()) {
            String manifestDigest = jdbcTemplate.queryForObject(
                    "SELECT sha256_digest FROM capability_manifest WHERE id = ? AND version = ?",
                    String.class,
                    manifest.metadata().id(),
                    manifest.metadata().version());
            if (manifestDigest == null || manifestDigest.isBlank()) {
                throw new IllegalStateException("Manifest digest not found: "
                        + manifest.metadata().id() + ":" + manifest.metadata().version());
            }
            jdbcTemplate.update(
                    "INSERT INTO catalog_snapshot_item (snapshot_version, capability_id, capability_version, manifest_digest, policy_ref) VALUES (?, ?, ?, ?, ?)",
                    snapshot.snapshotVersion(),
                    manifest.metadata().id(),
                    manifest.metadata().version(),
                    manifestDigest,
                    snapshot.policyRef());
            int lifecycleUpdated = jdbcTemplate.update(
                    "UPDATE capability_manifest SET lifecycle = 'PUBLISHED', updated_at = CURRENT_TIMESTAMP " +
                    "WHERE id = ? AND version = ? AND lifecycle IN ('APPROVED', 'PUBLISHED', 'SUSPENDED')",
                    manifest.metadata().id(), manifest.metadata().version());
            if (lifecycleUpdated != 1) {
                throw new IllegalStateException("Manifest is not publishable: "
                        + manifest.metadata().id() + ":" + manifest.metadata().version());
            }
        }
        appendPublishAudit(snapshot);
    }

    private void appendPublishAudit(CatalogSnapshot snapshot) {
        String eventType = "MANIFEST_PUBLISHED";
        String details = JsonbSupport.toJson(Map.of(
                "snapshotVersion", snapshot.snapshotVersion(),
                "environment", snapshot.environment(),
                "capabilityCount", snapshot.capabilities().size()));
        jdbcTemplate.update(
                "WITH inserted_audit AS (" +
                "INSERT INTO audit_event (event_type, timestamp, snapshot_version, result_code, details) " +
                "VALUES (?, CURRENT_TIMESTAMP, ?, 'PUBLISHED', ?::jsonb) RETURNING event_id) " +
                "INSERT INTO outbox_event (event_type, payload, audit_event_id) " +
                "SELECT ?, ?::jsonb, event_id FROM inserted_audit",
                eventType, snapshot.snapshotVersion(), details, eventType, details);
    }

    @Override
    public List<SnapshotSummary> listSnapshots(String environment, int limit) {
        Objects.requireNonNull(environment, "environment must not be null");
        if (limit < 1) limit = 10;

        String sql = "SELECT cs.snapshot_version, cs.environment, cs.status, cs.digest, " +
                "cs.published_at, cs.published_by, " +
                "COUNT(csi.capability_id) AS capability_count " +
                "FROM catalog_snapshot cs " +
                "LEFT JOIN catalog_snapshot_item csi ON cs.snapshot_version = csi.snapshot_version " +
                "WHERE cs.environment = ? " +
                "GROUP BY cs.snapshot_version, cs.environment, cs.status, cs.digest, " +
                "cs.published_at, cs.published_by " +
                "ORDER BY cs.snapshot_version DESC LIMIT ?";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp publishedAt = rs.getTimestamp("published_at");
            return new SnapshotSummary(
                    rs.getLong("snapshot_version"),
                    rs.getString("environment"),
                    rs.getString("status"),
                    rs.getString("digest"),
                    rs.getInt("capability_count"),
                    publishedAt != null ? publishedAt.toInstant() : java.time.Instant.now(),
                    rs.getString("published_by")
            );
        }, environment, limit);
    }
}
