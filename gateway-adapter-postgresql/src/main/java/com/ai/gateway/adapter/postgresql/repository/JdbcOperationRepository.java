package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.adapter.postgresql.JsonbSupport;
import com.ai.gateway.domain.model.ConfirmationSummary;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;
import com.ai.gateway.domain.port.OperationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC implementation of {@link OperationRepository} backed by PostgreSQL.
 *
 * <p>Persists write-operation records in the {@code operation_record} table
 * with version-based optimistic concurrency control. The
 * Confirm phase uses a conditional database update on version to atomically
 * claim execution, preventing duplicate execution when multiple confirm
 * attempts race.</p>
 *
 * <p>The CAS (Compare-And-Swap) state transition
 * updates the row only if the current state and version match the expected
 * values, atomically incrementing the version. This prevents lost updates
 * and duplicate execution.</p>
 *
 * @see OperationRepository
 * @since 0.1.0
 */
@Repository
public class JdbcOperationRepository implements OperationRepository {

    private static final String SQL_INSERT =
            "INSERT INTO operation_record (operation_id, state, principal_digest, org_id, " +
            "capability_id, capability_version, manifest_digest, snapshot_version, " +
            "encrypted_arguments, arguments_digest, idempotency_key, policy_decision_id, " +
            "confirmation_summary, expires_at, version) " +
            "VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)";

    private static final String SQL_INSERT_IF_ABSENT =
            SQL_INSERT + " ON CONFLICT (idempotency_key) DO NOTHING";

    private static final String SQL_FIND_BY_ID =
            "SELECT operation_id, state, principal_digest, org_id, capability_id, " +
            "capability_version, manifest_digest, snapshot_version, encrypted_arguments, " +
            "arguments_digest, idempotency_key, policy_decision_id, confirmation_summary, " +
            "expires_at, version FROM operation_record WHERE operation_id = CAST(? AS UUID)";

    private static final String SQL_FIND_BY_IDEMPOTENCY_KEY =
            "SELECT operation_id, state, principal_digest, org_id, capability_id, " +
            "capability_version, manifest_digest, snapshot_version, encrypted_arguments, " +
            "arguments_digest, idempotency_key, policy_decision_id, confirmation_summary, " +
            "expires_at, version FROM operation_record WHERE idempotency_key = ?";

    private static final String SQL_CAS_UPDATE_STATE =
            "UPDATE operation_record SET state = ?, version = version + 1, " +
            "updated_at = CURRENT_TIMESTAMP WHERE operation_id = CAST(? AS UUID) AND state = ? AND version = ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs a new JdbcOperationRepository.
     *
     * @param jdbcTemplate the Spring JDBC template for database access
     */
    public JdbcOperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public void save(OperationRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        jdbcTemplate.update(SQL_INSERT, ps -> bindRecord(ps, record));
    }

    @Override
    public OperationRecord saveOrGetByIdempotencyKey(OperationRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        int inserted = jdbcTemplate.update(
                SQL_INSERT_IF_ABSENT, ps -> bindRecord(ps, record));
        if (inserted == 1) {
            return record;
        }
        return findByIdempotencyKey(record.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency key conflict without an existing operation"));
    }

    private static void bindRecord(java.sql.PreparedStatement ps, OperationRecord record)
            throws java.sql.SQLException {
        ps.setString(1, record.operationId());
        ps.setString(2, record.state().name());
        ps.setString(3, record.principalDigest());
        ps.setLong(4, record.orgId());
        ps.setString(5, record.capabilityId());
        ps.setString(6, record.capabilityVersion());
        ps.setString(7, record.manifestDigest());
        ps.setLong(8, record.snapshotVersion());
        ps.setString(9, record.encryptedArguments());
        ps.setString(10, record.argumentsDigest());
        ps.setString(11, record.idempotencyKey());
        if (record.policyDecisionId() != null) {
            ps.setString(12, record.policyDecisionId());
        } else {
            ps.setNull(12, Types.VARCHAR);
        }
        JsonbSupport.setJsonbObject(ps, 13, record.confirmationSummary());
        ps.setTimestamp(14, Timestamp.from(record.expiresAt()));
        ps.setLong(15, record.version());
    }

    @Override
    public Optional<OperationRecord> findById(String operationId) {
        Objects.requireNonNull(operationId, "operationId must not be null");

        List<OperationRecord> results = jdbcTemplate.query(
                SQL_FIND_BY_ID,
                operationRowMapper(),
                operationId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<OperationRecord> findByIdempotencyKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        List<OperationRecord> results = jdbcTemplate.query(
                SQL_FIND_BY_IDEMPOTENCY_KEY, operationRowMapper(), idempotencyKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public boolean casUpdateState(String operationId, OperationState expectedState,
                                  OperationState newState, long expectedVersion) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(expectedState, "expectedState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");

        int affected = jdbcTemplate.update(SQL_CAS_UPDATE_STATE,
                newState.name(), operationId, expectedState.name(), expectedVersion);
        return affected == 1;
    }

    private static RowMapper<OperationRecord> operationRowMapper() {
        return (rs, rowNum) -> {
            String confirmationSummaryJson = rs.getString("confirmation_summary");
            ConfirmationSummary confirmationSummary = confirmationSummaryJson != null
                    ? JsonbSupport.fromJson(confirmationSummaryJson, ConfirmationSummary.class)
                    : null;

            Timestamp expiresAtTs = rs.getTimestamp("expires_at");
            Instant expiresAt = expiresAtTs != null ? expiresAtTs.toInstant() : null;

            String policyDecisionId = rs.getString("policy_decision_id");

            return new OperationRecord(
                    rs.getString("operation_id"),
                    OperationState.valueOf(rs.getString("state")),
                    rs.getString("principal_digest"),
                    rs.getLong("org_id"),
                    rs.getString("capability_id"),
                    rs.getString("capability_version"),
                    rs.getString("manifest_digest"),
                    rs.getLong("snapshot_version"),
                    rs.getString("encrypted_arguments"),
                    rs.getString("arguments_digest"),
                    rs.getString("idempotency_key"),
                    policyDecisionId,
                    confirmationSummary,
                    expiresAt,
                    rs.getLong("version"));
        };
    }
}
