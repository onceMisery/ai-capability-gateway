package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.adapter.postgresql.JsonbSupport;
import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ConfirmationSummary;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.ManifestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;

/**
 * JDBC implementation of {@link ManifestRepository} backed by PostgreSQL.
 *
 * <p>Persists Capability Manifests in the {@code capability_manifest} table.
 * The full manifest content is stored as JSONB in the {@code raw_content}
 * column; the SHA-256 content digest is stored separately for snapshot
 * integrity verification.</p>
 *
 * <p>The lifecycle state machine is persisted in the
 * {@code lifecycle} column. The same {@code id + version} content cannot
 * be overwritten; modifications must produce a new version.</p>
 *
 * @see ManifestRepository
 * @since 0.1.0
 */
@Repository
public class JdbcManifestRepository implements ManifestRepository {

    private static final String SQL_INSERT =
            "INSERT INTO capability_manifest (id, version, raw_content, sha256_digest, " +
            "owner_team, owner_contact, lifecycle) VALUES (?, ?, ?::jsonb, ?, ?, ?, ?)";

    private static final String SQL_FIND_BY_ID_AND_VERSION =
            "SELECT raw_content FROM capability_manifest WHERE id = ? AND version = ?";

    private static final String SQL_FIND_ALL = "SELECT raw_content FROM capability_manifest";

    private static final String SQL_FIND_ALL_WITH_DETAILS =
            "SELECT raw_content, lifecycle, sha256_digest, updated_at FROM capability_manifest";

    private static final String SQL_UPDATE_LIFECYCLE =
            "UPDATE capability_manifest SET lifecycle = ?, updated_at = CURRENT_TIMESTAMP " +
            "WHERE id = ? AND version = ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs a new JdbcManifestRepository.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     */
    public JdbcManifestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @Transactional
    public void save(CapabilityManifest manifest, String sha256Digest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(sha256Digest, "sha256Digest must not be null");

        String rawContent = JsonbSupport.toJson(manifest);
        String ownerTeam = manifest.metadata().owner().team();
        String ownerContact = manifest.metadata().owner().contact();
        String lifecycle = CapabilityLifecycle.DRAFT.name();

        jdbcTemplate.update(SQL_INSERT,
                manifest.metadata().id(),
                manifest.metadata().version(),
                rawContent,
                sha256Digest,
                ownerTeam,
                ownerContact,
                lifecycle);
        appendAuditAndOutbox("MANIFEST_IMPORTED", manifest.metadata().id(),
                manifest.metadata().version(), "IMPORTED", "{}");
    }

    @Override
    public Optional<CapabilityManifest> findByIdAndVersion(String id, String version) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");

        List<CapabilityManifest> results = jdbcTemplate.query(
                SQL_FIND_BY_ID_AND_VERSION,
                manifestRowMapper(),
                id, version);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<CapabilityManifest> findAll() {
        return jdbcTemplate.query(SQL_FIND_ALL, manifestRowMapper());
    }

    @Override
    public List<ManifestDetail> findAllWithDetails() {
        return jdbcTemplate.query(SQL_FIND_ALL_WITH_DETAILS, (rs, rowNum) -> {
            String rawContent = rs.getString("raw_content");
            CapabilityManifest manifest = JsonbSupport.fromJson(rawContent, CapabilityManifest.class);
            CapabilityLifecycle lifecycle = parseLifecycle(rs.getString("lifecycle"));
            String sha256Digest = rs.getString("sha256_digest");
            Timestamp ts = rs.getTimestamp("updated_at");
            Instant updatedAt = ts != null ? ts.toInstant() : Instant.now();
            return new ManifestDetail(manifest, lifecycle, sha256Digest, updatedAt);
        });
    }

    private static CapabilityLifecycle parseLifecycle(String value) {
        if (value == null) return CapabilityLifecycle.DRAFT;
        try {
            return CapabilityLifecycle.valueOf(value);
        } catch (IllegalArgumentException e) {
            return CapabilityLifecycle.DRAFT;
        }
    }

    @Override
    @Transactional
    public void updateLifecycle(String id, String version, CapabilityLifecycle lifecycle) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");

        int updated = jdbcTemplate.update(SQL_UPDATE_LIFECYCLE, lifecycle.name(), id, version);
        if (updated != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException(
                    "Expected one manifest lifecycle row, updated " + updated
                            + ": " + id + ":" + version);
        }
        appendAuditAndOutbox("MANIFEST_" + lifecycle.name(), id, version,
                lifecycle.name(), "{}");
    }

    @Override
    @Transactional
    public void recordValidation(String id, String version, ValidationReport report) {
        Objects.requireNonNull(report, "report must not be null");
        String reportJson = JsonbSupport.toJson(report);
        jdbcTemplate.update(
                "INSERT INTO capability_validation (manifest_id, manifest_version, report, validated_at) " +
                "VALUES (?, ?, ?::jsonb, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (manifest_id, manifest_version) DO UPDATE " +
                "SET report = EXCLUDED.report, validated_at = CURRENT_TIMESTAMP",
                id, version, reportJson);
        if (report.valid()) {
            int updated = jdbcTemplate.update(
                    "UPDATE capability_manifest SET lifecycle = 'VALIDATED', updated_at = CURRENT_TIMESTAMP " +
                    "WHERE id = ? AND version = ? AND lifecycle IN ('DRAFT', 'VALIDATED', 'SUSPENDED')",
                    id, version);
            requireSingleRow(updated, id, version, "validation");
        }
        appendAuditAndOutbox("MANIFEST_VALIDATION", id, version,
                report.valid() ? "VALIDATED" : "INVALID", reportJson);
    }

    @Override
    @Transactional
    public void recordApproval(String id, String version, String approver,
                               String decision, ConfirmationSummary summary) {
        Objects.requireNonNull(approver, "approver must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        CapabilityLifecycle target = "APPROVED".equals(decision)
                ? CapabilityLifecycle.APPROVED : CapabilityLifecycle.REJECTED;
        String summaryJson = JsonbSupport.toJson(summary != null ? summary : Map.of());
        jdbcTemplate.update(
                "INSERT INTO capability_approval (manifest_id, manifest_version, approver, role, decision, summary, approved_at) " +
                "VALUES (?, ?, ?, 'ADMIN', ?, ?::jsonb, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (manifest_id, manifest_version) DO UPDATE SET " +
                "approver = EXCLUDED.approver, role = EXCLUDED.role, decision = EXCLUDED.decision, " +
                "summary = EXCLUDED.summary, approved_at = CURRENT_TIMESTAMP",
                id, version, approver, decision, summaryJson);
        int updated = jdbcTemplate.update(
                "UPDATE capability_manifest SET lifecycle = ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = ? AND version = ? AND lifecycle = 'VALIDATED'",
                target.name(), id, version);
        requireSingleRow(updated, id, version, "approval");
        appendAuditAndOutbox("MANIFEST_" + target.name(), id, version,
                decision, summaryJson);
    }

    private void appendAuditAndOutbox(String eventType, String id, String version,
                                      String resultCode, String detailsJson) {
        String sql = "WITH inserted_audit AS (" +
                "INSERT INTO audit_event (event_type, timestamp, capability_id, capability_version, result_code, details) " +
                "VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?::jsonb) RETURNING event_id) " +
                "INSERT INTO outbox_event (event_type, payload, audit_event_id) " +
                "SELECT ?, ?::jsonb, event_id FROM inserted_audit";
        String payload = JsonbSupport.toJson(Map.of(
                "eventType", eventType, "capabilityId", id,
                "capabilityVersion", version, "resultCode", resultCode));
        jdbcTemplate.update(sql, eventType, id, version, resultCode, detailsJson,
                eventType, payload);
    }

    private static void requireSingleRow(int updated, String id, String version, String operation) {
        if (updated != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException(
                    "Expected one manifest row for " + operation + ", updated " + updated
                            + ": " + id + ":" + version);
        }
    }

    private static RowMapper<CapabilityManifest> manifestRowMapper() {
        return (rs, rowNum) -> {
            String rawContent = rs.getString("raw_content");
            return JsonbSupport.fromJson(rawContent, CapabilityManifest.class);
        };
    }
}
