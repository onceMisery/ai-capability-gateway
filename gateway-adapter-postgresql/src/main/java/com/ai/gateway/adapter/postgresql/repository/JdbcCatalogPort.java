package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.adapter.postgresql.JsonbSupport;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.SnapshotSummary;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;
import com.ai.gateway.domain.service.ManifestDigest;
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

    private static final String SQL_FIND_SNAPSHOT_CONTENT =
            "SELECT csi.capability_id, csi.capability_version, csi.manifest_digest, csi.policy_ref, cm.raw_content " +
            "FROM catalog_snapshot_item csi " +
            "LEFT JOIN capability_manifest cm " +
            "  ON cm.id = csi.capability_id AND cm.version = csi.capability_version " +
            "WHERE csi.snapshot_version = ?";

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
        // Allocate the next version as the greater of the sequence's next value
        // and (max existing version + 1). Relying solely on the sequence can
        // collide when the catalog_snapshot table already holds rows whose
        // snapshot_version was not produced by the sequence (e.g. a reseeded or
        // reset sequence, a manually inserted row, or a partially applied
        // migration). Taking max(version)+1 keeps the allocation monotonic and
        // collision-free regardless of the sequence's current position.
        Long version = jdbcTemplate.queryForObject(
                "SELECT GREATEST(" +
                "nextval('catalog_snapshot_snapshot_version_seq'), " +
                "COALESCE((SELECT MAX(snapshot_version) FROM catalog_snapshot), 0) + 1)",
                Long.class);
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
        // Single JOIN query fetches items and their manifest content together,
        // avoiding the previous N+1 per-item manifest lookups.
        List<ContentRow> rows = jdbcTemplate.query(
                SQL_FIND_SNAPSHOT_CONTENT,
                contentRowMapper(),
                snapshot.snapshotVersion());

        List<CapabilityManifest> capabilities = new ArrayList<>(rows.size());
        String policyRef = null;

        for (ContentRow row : rows) {
            if (row.policyRef() != null) {
                if (policyRef != null && !policyRef.equals(row.policyRef())) {
                    throw new IllegalStateException("Snapshot policy reference is inconsistent: "
                            + snapshot.snapshotVersion());
                }
                policyRef = row.policyRef();
            }
            if (row.rawContent() == null) {
                throw new IllegalStateException("Snapshot Manifest content is missing: "
                        + row.capabilityId() + ":" + row.capabilityVersion());
            }
            CapabilityManifest manifest = JsonbSupport.fromJson(row.rawContent(), CapabilityManifest.class);
            if (!Objects.equals(row.manifestDigest(), ManifestDigest.sha256(manifest))) {
                throw new IllegalStateException("Manifest digest verification failed: "
                        + row.capabilityId() + ":" + row.capabilityVersion());
            }
            capabilities.add(manifest);
        }

        CatalogSnapshot loaded = new CatalogSnapshot(
                snapshot.snapshotVersion(),
                snapshot.environment(),
                List.copyOf(capabilities),
                policyRef,
                snapshot.digest());
        if (!Objects.equals(CatalogSnapshotDigest.sha256(loaded), snapshot.digest())) {
            throw new IllegalStateException("Snapshot digest verification failed: "
                    + snapshot.snapshotVersion());
        }
        return loaded;
    }

    private static RowMapper<SnapshotRow> snapshotRowMapper() {
        return (rs, rowNum) -> new SnapshotRow(
                rs.getLong("snapshot_version"),
                rs.getString("environment"),
                rs.getString("digest"));
    }

    private static RowMapper<ContentRow> contentRowMapper() {
        return (rs, rowNum) -> new ContentRow(
                rs.getString("capability_id"),
                rs.getString("capability_version"),
                rs.getString("manifest_digest"),
                rs.getString("policy_ref"),
                rs.getString("raw_content"));
    }

    private static RowMapper<CapabilityManifest> manifestRowMapper() {
        return (rs, rowNum) -> {
            String rawContent = rs.getString("raw_content");
            return JsonbSupport.fromJson(rawContent, CapabilityManifest.class);
        };
    }

    private record SnapshotRow(long snapshotVersion, String environment, String digest) {
    }

    private record ContentRow(String capabilityId, String capabilityVersion,
                              String manifestDigest, String policyRef, String rawContent) {
    }

    @Override
    public void lockEnvironmentForPublication(String environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        jdbcTemplate.queryForObject(
                "SELECT 1 FROM pg_advisory_xact_lock(hashtext(?))",
                Integer.class,
                environment);
    }

    @Override
    @Transactional
    public void saveSnapshot(CatalogSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // Keep a defensive lock here for direct adapter callers. Application
        // use cases acquire the same transaction-scoped lock before reading
        // manifests or allocating a snapshot version.
        lockEnvironmentForPublication(snapshot.environment());

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
            if (!manifestDigest.equals(ManifestDigest.sha256(manifest))) {
                throw new IllegalStateException("Stored Manifest digest does not match content: "
                        + manifest.metadata().id() + ":" + manifest.metadata().version());
            }
            jdbcTemplate.update(
                    "INSERT INTO catalog_snapshot_item (snapshot_version, capability_id, capability_version, manifest_digest, policy_ref) VALUES (?, ?, ?, ?, ?)",
                    snapshot.snapshotVersion(),
                    manifest.metadata().id(),
                    manifest.metadata().version(),
                    manifestDigest,
                    snapshot.policyRef());
        }
    }

    @Override
    public void recordSnapshotPublication(CatalogSnapshot snapshot, String eventType) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        String details = JsonbSupport.toJson(Map.of(
                "snapshotVersion", snapshot.snapshotVersion(),
                "environment", snapshot.environment(),
                "capabilityCount", snapshot.capabilities().size()));
        jdbcTemplate.update(
                "WITH inserted_audit AS (" +
                "INSERT INTO audit_event (event_type, timestamp, snapshot_version, result_code, details) " +
                "VALUES (?, CURRENT_TIMESTAMP, ?, 'PUBLISHED', ?::jsonb) " +
                "ON CONFLICT DO NOTHING RETURNING event_id) " +
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
