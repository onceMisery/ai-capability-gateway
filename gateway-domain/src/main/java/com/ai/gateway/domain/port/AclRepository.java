package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.CapabilityAclEntry;
import com.ai.gateway.domain.model.Permission;
import com.ai.gateway.domain.model.Role;

import java.util.List;
import java.util.Optional;

/**
 * Port for managing capability access-control lists, roles, and permissions.
 *
 * <p>Defines the persistence operations for the admin console's permission
 * management. Capability-level ACL entries map each capability to the set
 * of roles allowed to invoke it. Roles group permission words. Permission
 * words follow the {@code domain:resource:action} convention.</p>
 *
 * <p>ACL changes are persisted immediately and take effect on the next
 * authorization check. The authorization adapter refreshes its in-memory
 * ACL cache after each mutation.</p>
 *
 * @see CapabilityAclEntry
 * @see Role
 * @see Permission
 * @since 0.1.0
 */
public interface AclRepository {

    /** Returns the durable monotonic epoch of ACL, role, and permission state. */
    default long currentPolicyEpoch() {
        return 0L;
    }

    /** Atomically increments and returns the durable policy epoch. */
    default long incrementPolicyEpoch() {
        throw new UnsupportedOperationException("policy epoch is not supported");
    }

    // ================================================================
    // ACL Entry operations
    // ================================================================

    /**
     * Returns all ACL entries.
     *
     * @return all capability ACL entries; never {@code null}
     */
    List<CapabilityAclEntry> findAllAclEntries();

    /**
     * Returns the ACL entry for a specific capability.
     *
     * @param capabilityId the capability identifier
     * @param capabilityVersion the capability version
     * @return the ACL entry, or empty if not found
     */
    Optional<CapabilityAclEntry> findAclEntry(String capabilityId, String capabilityVersion);

    /**
     * Saves or updates an ACL entry.
     *
     * @param entry the ACL entry to persist
     */
    void saveAclEntry(CapabilityAclEntry entry);

    /**
     * Deletes an ACL entry.
     *
     * @param capabilityId the capability identifier
     * @param capabilityVersion the capability version
     */
    void deleteAclEntry(String capabilityId, String capabilityVersion);

    // ================================================================
    // Role operations
    // ================================================================

    /**
     * Returns all roles.
     *
     * @return all roles; never {@code null}
     */
    List<Role> findAllRoles();

    /**
     * Finds a role by name.
     *
     * @param name the role name
     * @return the role, or empty if not found
     */
    Optional<Role> findRoleByName(String name);

    /**
     * Saves or updates a role.
     *
     * @param role the role to persist
     */
    void saveRole(Role role);

    /**
     * Deletes a role by name.
     *
     * @param name the role name
     */
    void deleteRole(String name);

    // ================================================================
    // Permission operations
    // ================================================================

    /**
     * Returns all permissions.
     *
     * @return all permissions; never {@code null}
     */
    List<Permission> findAllPermissions();

    /**
     * Saves or updates a permission.
     *
     * @param permission the permission to persist
     */
    void savePermission(Permission permission);

    /**
     * Deletes a permission by name.
     *
     * @param name the permission name
     */
    void deletePermission(String name);
}
