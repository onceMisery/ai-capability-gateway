package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.CapabilityAclEntry;
import com.ai.gateway.domain.model.Permission;
import com.ai.gateway.domain.model.Role;

import java.util.List;
import java.util.Optional;

/**
 * 管理能力访问控制列表（ACL）、角色与权限的端口。
 *
 * <p>定义管理控制台权限管理的持久化操作。能力级 ACL 条目将每个能力映射到允许调用它的
 * 角色集合。角色聚合权限词。权限词遵循 {@code domain:resource:action} 约定。</p>
 *
 * <p>ACL 变更立即持久化，并在下一次鉴权检查时生效。鉴权适配器在每次变更后刷新其内存中的
 * ACL 缓存。</p>
 *
 * @see CapabilityAclEntry
 * @see Role
 * @see Permission
 * @since 0.1.0
 */
public interface AclRepository {

    /** 返回 ACL、角色与权限状态的持久化单调递增纪元（epoch）。 */
    default long currentPolicyEpoch() {
        return 0L;
    }

    /** 原子地递增并返回持久化策略纪元。 */
    default long incrementPolicyEpoch() {
        throw new UnsupportedOperationException("policy epoch is not supported");
    }

    // ================================================================
    // ACL 条目操作
    // ================================================================

    /**
     * 返回所有 ACL 条目。
     *
     * @return 全部能力 ACL 条目；永不为 {@code null}
     */
    List<CapabilityAclEntry> findAllAclEntries();

    /**
     * 返回特定能力的 ACL 条目。
     *
     * @param capabilityId 能力标识
     * @param capabilityVersion 能力版本
     * @return ACL 条目，未找到时为 empty
     */
    Optional<CapabilityAclEntry> findAclEntry(String capabilityId, String capabilityVersion);

    /**
     * 保存或更新一个 ACL 条目。
     *
     * @param entry 待持久化的 ACL 条目
     */
    void saveAclEntry(CapabilityAclEntry entry);

    /**
     * 删除一个 ACL 条目。
     *
     * @param capabilityId 能力标识
     * @param capabilityVersion 能力版本
     */
    void deleteAclEntry(String capabilityId, String capabilityVersion);

    // ================================================================
    // 角色操作
    // ================================================================

    /**
     * 返回所有角色。
     *
     * @return 全部角色；永不为 {@code null}
     */
    List<Role> findAllRoles();

    /**
     * 按名查找角色。
     *
     * @param name 角色名
     * @return 角色，未找到时为 empty
     */
    Optional<Role> findRoleByName(String name);

    /**
     * 保存或更新一个角色。
     *
     * @param role 待持久化的角色
     */
    void saveRole(Role role);

    /**
     * 按名删除角色。
     *
     * @param name 角色名
     */
    void deleteRole(String name);

    // ================================================================
    // 权限操作
    // ================================================================

    /**
     * 返回所有权限。
     *
     * @return 全部权限；永不为 {@code null}
     */
    List<Permission> findAllPermissions();

    /**
     * 保存或更新一个权限。
     *
     * @param permission 待持久化的权限
     */
    void savePermission(Permission permission);

    /**
     * 按名删除权限。
     *
     * @param name 权限名
     */
    void deletePermission(String name);
}
