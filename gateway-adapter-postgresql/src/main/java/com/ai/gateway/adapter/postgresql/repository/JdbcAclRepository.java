package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.adapter.postgresql.JsonbSupport;
import com.ai.gateway.domain.model.CapabilityAclEntry;
import com.ai.gateway.domain.model.Permission;
import com.ai.gateway.domain.model.Role;
import com.ai.gateway.domain.port.AclRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link AclRepository} 基于 PostgreSQL 的 JDBC 实现。
 *
 * <p>将能力 ACL 条目、角色与权限分别持久化到 {@code capability_acl}、{@code gateway_role}
 * 与 {@code gateway_permission} 表中。</p>
 *
 * @author cmiracle@163.com
 * @see AclRepository
 * @since 0.1.0
 */
@Repository
public class JdbcAclRepository implements AclRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造一个新的 JdbcAclRepository。
     *
     * @param jdbcTemplate 用于数据库访问的 Spring JDBC 模板
     */
    public JdbcAclRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public long currentPolicyEpoch() {
        Long epoch = jdbcTemplate.queryForObject(
                "SELECT policy_epoch FROM acl_policy_epoch WHERE singleton_id = 1",
                Long.class);
        return epoch == null ? 0L : epoch;
    }

    @Override
    public long incrementPolicyEpoch() {
        Long epoch = jdbcTemplate.queryForObject(
                "UPDATE acl_policy_epoch SET policy_epoch = policy_epoch + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE singleton_id = 1 "
                        + "RETURNING policy_epoch",
                Long.class);
        return Objects.requireNonNull(epoch, "policy epoch update returned no row");
    }

    // ================================================================
    // ACL 条目操作
    // ================================================================

    @Override
    public List<CapabilityAclEntry> findAllAclEntries() {
        return jdbcTemplate.query(
                "SELECT capability_id, capability_version, allowed_roles, required_permissions, updated_at, updated_by " +
                "FROM capability_acl ORDER BY capability_id, capability_version",
                aclEntryRowMapper());
    }

    @Override
    public Optional<CapabilityAclEntry> findAclEntry(String capabilityId, String capabilityVersion) {
        List<CapabilityAclEntry> results = jdbcTemplate.query(
                "SELECT capability_id, capability_version, allowed_roles, required_permissions, updated_at, updated_by " +
                "FROM capability_acl WHERE capability_id = ? AND capability_version = ?",
                aclEntryRowMapper(), capabilityId, capabilityVersion);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    @Transactional
    public void saveAclEntry(CapabilityAclEntry entry) {
        String allowedRolesJson = JsonbSupport.toJson(entry.allowedRoles());
        String requiredPermissionsJson = JsonbSupport.toJson(entry.requiredPermissions());

        int updated = jdbcTemplate.update(
                "UPDATE capability_acl SET allowed_roles = ?::jsonb, required_permissions = ?::jsonb, " +
                "updated_at = ?, updated_by = ? WHERE capability_id = ? AND capability_version = ?",
                allowedRolesJson, requiredPermissionsJson,
                Timestamp.from(entry.updatedAt()), entry.updatedBy(),
                entry.capabilityId(), entry.capabilityVersion());

        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO capability_acl (capability_id, capability_version, allowed_roles, " +
                    "required_permissions, updated_at, updated_by) VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                    entry.capabilityId(), entry.capabilityVersion(),
                    allowedRolesJson, requiredPermissionsJson,
                    Timestamp.from(entry.updatedAt()), entry.updatedBy());
        }
        incrementPolicyEpoch();
    }

    @Override
    @Transactional
    public void deleteAclEntry(String capabilityId, String capabilityVersion) {
        jdbcTemplate.update(
                "DELETE FROM capability_acl WHERE capability_id = ? AND capability_version = ?",
                capabilityId, capabilityVersion);
        incrementPolicyEpoch();
    }

    // ================================================================
    // 角色操作
    // ================================================================

    @Override
    public List<Role> findAllRoles() {
        return jdbcTemplate.query(
                "SELECT name, description, permissions, created_at, updated_at FROM gateway_role ORDER BY name",
                roleRowMapper());
    }

    @Override
    public Optional<Role> findRoleByName(String name) {
        List<Role> results = jdbcTemplate.query(
                "SELECT name, description, permissions, created_at, updated_at FROM gateway_role WHERE name = ?",
                roleRowMapper(), name);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    @Transactional
    public void saveRole(Role role) {
        String permissionsJson = JsonbSupport.toJson(role.permissions());

        int updated = jdbcTemplate.update(
                "UPDATE gateway_role SET description = ?, permissions = ?::jsonb, updated_at = ? WHERE name = ?",
                role.description(), permissionsJson, Timestamp.from(role.updatedAt()), role.name());

        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO gateway_role (name, description, permissions, created_at, updated_at) " +
                    "VALUES (?, ?, ?::jsonb, ?, ?)",
                    role.name(), role.description(), permissionsJson,
                    Timestamp.from(role.createdAt()), Timestamp.from(role.updatedAt()));
        }
        incrementPolicyEpoch();
    }

    @Override
    @Transactional
    public void deleteRole(String name) {
        jdbcTemplate.update("DELETE FROM gateway_role WHERE name = ?", name);
        incrementPolicyEpoch();
    }

    // ================================================================
    // 权限操作
    // ================================================================

    @Override
    public List<Permission> findAllPermissions() {
        return jdbcTemplate.query(
                "SELECT name, description, created_at FROM gateway_permission ORDER BY name",
                permissionRowMapper());
    }

    @Override
    @Transactional
    public void savePermission(Permission permission) {
        int updated = jdbcTemplate.update(
                "UPDATE gateway_permission SET description = ? WHERE name = ?",
                permission.description(), permission.name());

        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO gateway_permission (name, description, created_at) VALUES (?, ?, ?)",
                    permission.name(), permission.description(), Timestamp.from(permission.createdAt()));
        }
        incrementPolicyEpoch();
    }

    @Override
    @Transactional
    public void deletePermission(String name) {
        jdbcTemplate.update("DELETE FROM gateway_permission WHERE name = ?", name);
        incrementPolicyEpoch();
    }

    // ================================================================
    // 行映射器
    // ================================================================

    private static RowMapper<CapabilityAclEntry> aclEntryRowMapper() {
        return (rs, rowNum) -> {
            String allowedRolesStr = rs.getString("allowed_roles");
            String requiredPermissionsStr = rs.getString("required_permissions");
            List<String> allowedRoles = allowedRolesStr != null
                    ? JsonbSupport.fromJson(allowedRolesStr, List.class) : List.of();
            List<String> requiredPermissions = requiredPermissionsStr != null
                    ? JsonbSupport.fromJson(requiredPermissionsStr, List.class) : List.of();

            return new CapabilityAclEntry(
                    rs.getString("capability_id"),
                    rs.getString("capability_version"),
                    allowedRoles,
                    requiredPermissions,
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("updated_by")
            );
        };
    }

    private static RowMapper<Role> roleRowMapper() {
        return (rs, rowNum) -> {
            String permissionsStr = rs.getString("permissions");
            List<String> permissions = permissionsStr != null
                    ? JsonbSupport.fromJson(permissionsStr, List.class) : List.of();

            return new Role(
                    rs.getString("name"),
                    rs.getString("description"),
                    permissions,
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
        };
    }

    private static RowMapper<Permission> permissionRowMapper() {
        return (rs, rowNum) -> new Permission(
                rs.getString("name"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
