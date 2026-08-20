package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.adapter.postgresql.JsonbSupport;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.SnapshotSummary;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;
import com.ai.gateway.domain.service.ManifestDigest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.ObjectProvider;
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
 * {@link CatalogPort} 基于 PostgreSQL 的 JDBC 实现。
 *
 * <p>从 {@code catalog_snapshot} 与 {@code catalog_snapshot_item} 表加载目录快照。每个快照一旦
 * 发布即不可变；回滚会将历史快照的内容复制到一个新的快照版本。</p>
 *
 * <p>当前活跃快照由 {@code status = 'ACTIVE'} 标识。快照项通过 ID 与版本引用能力清单；完整清单
 * 内容从 {@code capability_manifest} 加载以重建 {@link CatalogSnapshot}。</p>
 *
 * @author cmiracle@163.com
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
    private final CatalogReadBudget readBudget;

    /**
     * 构造一个新的 JdbcCatalogPort。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     */
    public JdbcCatalogPort(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.readBudget = new CatalogReadBudget(10_000, 30, 128L * 1024L * 1024L);
    }

    public JdbcCatalogPort(JdbcTemplate jdbcTemplate, CatalogReadBudget readBudget) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.readBudget = Objects.requireNonNull(readBudget, "readBudget must not be null");
    }

    @Autowired
    public JdbcCatalogPort(JdbcTemplate jdbcTemplate,
                           ObjectProvider<CatalogReadBudget> readBudgetProvider) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.readBudget = readBudgetProvider.getIfAvailable(
                () -> new CatalogReadBudget(10_000, 30, 128L * 1024L * 1024L));
    }

    @Override
    public long reserveSnapshotVersion() {
        // 将下一版本号分配为序列的下一个值与（最大现有版本 + 1）中的较大者。仅依赖序列可能
        // 在 catalog_snapshot 表中已存在由非序列产生的快照版本行时发生冲突（例如重新播种或重置的
        // 序列、手动插入的行，或应用了一半的迁移）。取 max(version)+1 可使分配保持单调且
        // 不冲突，而与序列的当前位置无关。
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

        List<SnapshotRow> snapshots = jdbcTemplate.query(SQL_FIND_ACTIVE_SNAPSHOT,
                statement -> {
                    configureRead(statement);
                    statement.setString(1, environment);
                }, snapshotRowMapper());
        if (snapshots.isEmpty()) {
            return new CatalogSnapshot(0L, environment, List.of(), null, "");
        }
        return loadSnapshotInternal(snapshots.get(0));
    }

    @Override
    public CatalogSnapshot loadSnapshot(long snapshotVersion) {
        List<SnapshotRow> snapshots = jdbcTemplate.query(SQL_FIND_SNAPSHOT_BY_VERSION,
                statement -> {
                    configureRead(statement);
                    statement.setLong(1, snapshotVersion);
                }, snapshotRowMapper());
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
        // 使用单条 JOIN 查询一次性取回条目及其清单内容，避免此前逐个条目清单查找的 N+1 问题。
        List<ContentRow> rows = jdbcTemplate.query(SQL_FIND_SNAPSHOT_CONTENT,
                statement -> {
                    configureRead(statement);
                    statement.setLong(1, snapshot.snapshotVersion());
                }, contentRowMapper());

        List<CapabilityManifest> capabilities = new ArrayList<>(rows.size());
        String policyRef = null;
        long payloadBytes = 0L;

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
            payloadBytes += row.rawContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (payloadBytes > readBudget.maxPayloadBytes()) {
                throw new IllegalStateException("Catalog snapshot payload exceeds read budget");
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

    private void configureRead(java.sql.PreparedStatement statement) throws java.sql.SQLException {
        statement.setMaxRows(readBudget.maxRows());
        statement.setQueryTimeout(readBudget.queryTimeoutSeconds());
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

        // 这里保留一个防御性锁，用于直接调用适配器的调用方。应用用例会在读取清单或分配快照
        // 版本之前获取同一个事务作用域内的锁。
        lockEnvironmentForPublication(snapshot.environment());

        // 将之前的 ACTIVE 快照标记为 SUPERSEDED
        jdbcTemplate.update(
                "UPDATE catalog_snapshot SET status = 'SUPERSEDED' WHERE environment = ? AND status = 'ACTIVE'",
                snapshot.environment());

        // 插入新快照
        jdbcTemplate.update(
                "INSERT INTO catalog_snapshot (snapshot_version, environment, status, digest) VALUES (?, ?, 'ACTIVE', ?)",
                snapshot.snapshotVersion(), snapshot.environment(),
                snapshot.digest() != null ? snapshot.digest() : "");

        // 插入快照条目
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
