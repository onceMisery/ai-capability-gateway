package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.adapter.postgresql.JsonbSupport;
import com.ai.gateway.domain.model.NlInteraction;
import com.ai.gateway.domain.port.InteractionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link InteractionRepository} 基于 PostgreSQL 的 JDBC 实现。
 *
 * <p>将澄清交互会话持久化到 {@code nl_interaction} 表。每个交互具有较短 TTL，并在检测到意图跳转、
 * 主体（Principal）变更、会话过期、能力被暂停或策略变更时失效。</p>
 *
 * <p>候选项能力 ID、已确认参数与待填充字段以 JSONB 列存储，以支持灵活的模式演进。</p>
 *
 * @author cmiracle@163.com
 * @see InteractionRepository
 * @since 0.1.0
 */
@Repository
public class JdbcInteractionRepository implements InteractionRepository {

    private static final String SQL_INSERT =
            "INSERT INTO nl_interaction (interaction_id, principal_digest, snapshot_version, " +
            "candidates, confirmed_params, pending_fields, expires_at) " +
            "VALUES (CAST(? AS UUID), ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?) " +
            "ON CONFLICT (interaction_id) DO UPDATE SET " +
            "principal_digest = EXCLUDED.principal_digest, " +
            "snapshot_version = EXCLUDED.snapshot_version, " +
            "candidates = EXCLUDED.candidates, " +
            "confirmed_params = EXCLUDED.confirmed_params, " +
            "pending_fields = EXCLUDED.pending_fields, " +
            "expires_at = EXCLUDED.expires_at";

    private static final String SQL_FIND_BY_ID =
            "SELECT interaction_id, principal_digest, snapshot_version, candidates, " +
            "confirmed_params, pending_fields, expires_at " +
            "FROM nl_interaction WHERE interaction_id = CAST(? AS UUID)";

    private static final String SQL_DELETE_BY_ID =
            "DELETE FROM nl_interaction WHERE interaction_id = CAST(? AS UUID)";

    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP =
            new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造一个新的 JdbcInteractionRepository。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     */
    public JdbcInteractionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public void save(NlInteraction interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");

        jdbcTemplate.update(SQL_INSERT, ps -> {
            ps.setString(1, interaction.interactionId());
            ps.setString(2, interaction.principalDigest());
            ps.setLong(3, interaction.snapshotVersion());
            JsonbSupport.setJsonb(ps, 4, JsonbSupport.toJson(interaction.candidateCapabilityIds()));
            JsonbSupport.setJsonb(ps, 5, JsonbSupport.toJson(interaction.confirmedParams()));
            JsonbSupport.setJsonb(ps, 6, JsonbSupport.toJson(interaction.pendingFields()));
            ps.setTimestamp(7, Timestamp.from(interaction.expiresAt()));
        });
    }

    @Override
    public Optional<NlInteraction> findById(String interactionId) {
        Objects.requireNonNull(interactionId, "interactionId must not be null");

        List<NlInteraction> results = jdbcTemplate.query(
                SQL_FIND_BY_ID,
                interactionRowMapper(),
                interactionId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void deleteById(String interactionId) {
        Objects.requireNonNull(interactionId, "interactionId must not be null");
        jdbcTemplate.update(SQL_DELETE_BY_ID, interactionId);
    }

    private static RowMapper<NlInteraction> interactionRowMapper() {
        return (rs, rowNum) -> {
            String candidatesJson = rs.getString("candidates");
            List<String> candidateCapabilityIds = JsonbSupport.fromJson(candidatesJson, STRING_LIST);

            String confirmedParamsJson = rs.getString("confirmed_params");
            Map<String, Object> confirmedParams = confirmedParamsJson != null
                    ? JsonbSupport.fromJson(confirmedParamsJson, OBJECT_MAP)
                    : Map.of();

            String pendingFieldsJson = rs.getString("pending_fields");
            List<String> pendingFields = pendingFieldsJson != null
                    ? JsonbSupport.fromJson(pendingFieldsJson, STRING_LIST)
                    : List.of();

            Timestamp expiresAtTs = rs.getTimestamp("expires_at");
            Instant expiresAt = expiresAtTs.toInstant();

            return new NlInteraction(
                    rs.getString("interaction_id"),
                    rs.getString("principal_digest"),
                    rs.getLong("snapshot_version"),
                    candidateCapabilityIds,
                    confirmedParams,
                    pendingFields,
                    expiresAt);
        };
    }
}
