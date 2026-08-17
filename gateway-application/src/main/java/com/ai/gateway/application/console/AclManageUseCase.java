package com.ai.gateway.application.console;

import com.ai.gateway.domain.model.CapabilityAclEntry;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.Permission;
import com.ai.gateway.domain.model.Role;
import com.ai.gateway.domain.port.AclRepository;
import com.ai.gateway.domain.port.ManifestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Use case for managing capability ACL entries, roles, and permissions
 * from the admin console.
 *
 * <p>Provides CRUD operations for:</p>
 * <ul>
 * <li>Capability ACL entries: mapping capabilities to allowed roles.</li>
 * <li>Roles: named groups of permission words.</li>
 * <li>Permissions: {@code domain:resource:action} permission words.</li>
 * </ul>
 *
 * <p>ACL changes are persisted immediately. The authorization adapter
 * should refresh its in-memory cache after each mutation (handled by
 * the adapter layer).</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @since 0.1.0
 */
public final class AclManageUseCase {

    private static final Logger log = LoggerFactory.getLogger(AclManageUseCase.class);
    private static final Pattern PERMISSION_NAME = Pattern.compile(
            "^[a-z][a-z0-9]*(:[a-z][a-z0-9]*){2}$");

    private final AclRepository aclRepository;
    private final ManifestRepository manifestRepository;

    /**
     * Constructs a new AclManageUseCase.
     *
     * @param aclRepository the ACL repository
     * @param manifestRepository the authoritative Manifest repository
     */
    public AclManageUseCase(AclRepository aclRepository,
                            ManifestRepository manifestRepository) {
        this.aclRepository = Objects.requireNonNull(aclRepository);
        this.manifestRepository = Objects.requireNonNull(manifestRepository);
    }

    // ================================================================
    // ACL Entry operations
    // ================================================================

    /**
     * Lists all ACL entries.
     *
     * @return all capability ACL entries; never {@code null}
     */
    public List<CapabilityAclEntry> listAclEntries() {
        return aclRepository.findAllAclEntries();
    }

    /**
     * Gets the ACL entry for a specific capability.
     *
     * @param capabilityId the capability identifier
     * @param capabilityVersion the capability version
     * @return the ACL entry, or empty if not found
     */
    public Optional<CapabilityAclEntry> getAclEntry(String capabilityId, String capabilityVersion) {
        return aclRepository.findAclEntry(capabilityId, capabilityVersion);
    }

    /**
     * Saves or updates an ACL entry.
     *
     * @param capabilityId the capability identifier
     * @param capabilityVersion the capability version
     * @param allowedRoles the roles allowed to invoke this capability
     * @param updatedBy the identity performing the update
     */
    public void saveAclEntry(String capabilityId, String capabilityVersion,
                              List<String> allowedRoles, String updatedBy) {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(capabilityVersion, "capabilityVersion must not be null");
        if (allowedRoles == null || allowedRoles.isEmpty()
                || allowedRoles.stream().anyMatch(role -> role == null || role.isBlank())) {
            throw new IllegalArgumentException(
                    "allowedRoles must contain at least one non-blank role");
        }

        CapabilityManifest manifest = manifestRepository
                .findByIdAndVersion(capabilityId, capabilityVersion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Capability Manifest not found: " + capabilityId + ":" + capabilityVersion));
        List<String> requiredPermissions = manifest.spec().authorization() == null
                ? List.of()
                : manifest.spec().authorization().permissions();
        CapabilityAclEntry entry = new CapabilityAclEntry(
                capabilityId, capabilityVersion, allowedRoles, requiredPermissions,
                Instant.now(), updatedBy != null ? updatedBy : "system"
        );
        aclRepository.saveAclEntry(entry);
        log.info("ACL entry saved for capability {}:{} by {}", capabilityId, capabilityVersion, updatedBy);
    }

    /**
     * Deletes an ACL entry.
     *
     * @param capabilityId the capability identifier
     * @param capabilityVersion the capability version
     */
    public void deleteAclEntry(String capabilityId, String capabilityVersion) {
        aclRepository.deleteAclEntry(capabilityId, capabilityVersion);
        log.info("ACL entry deleted for capability {}:{}", capabilityId, capabilityVersion);
    }

    // ================================================================
    // Role operations
    // ================================================================

    /**
     * Lists all roles.
     *
     * @return all roles; never {@code null}
     */
    public List<Role> listRoles() {
        return aclRepository.findAllRoles();
    }

    /**
     * Gets a role by name.
     *
     * @param name the role name
     * @return the role, or empty if not found
     */
    public Optional<Role> getRole(String name) {
        return aclRepository.findRoleByName(name);
    }

    /**
     * Creates or updates a role.
     *
     * @param name the role name
     * @param description the role description
     * @param permissions the permission words assigned to this role
     */
    public void saveRole(String name, String description, List<String> permissions) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        if (permissions == null || permissions.stream().anyMatch(permission ->
                permission == null || !PERMISSION_NAME.matcher(permission).matches())) {
            throw new IllegalArgumentException(
                    "role permissions must use domain:resource:action names");
        }

        Instant now = Instant.now();
        Role role = new Role(name, description, permissions, now, now);
        aclRepository.saveRole(role);
        log.info("Role saved: {}", name);
    }

    /**
     * Deletes a role by name.
     *
     * @param name the role name
     */
    public void deleteRole(String name) {
        aclRepository.deleteRole(name);
        log.info("Role deleted: {}", name);
    }

    // ================================================================
    // Permission operations
    // ================================================================

    /**
     * Lists all permissions.
     *
     * @return all permissions; never {@code null}
     */
    public List<Permission> listPermissions() {
        return aclRepository.findAllPermissions();
    }

    /**
     * Creates or updates a permission.
     *
     * @param name the permission word (must match domain:resource:action pattern)
     * @param description the permission description
     */
    public void savePermission(String name, String description) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");

        Permission permission = new Permission(name, description, Instant.now());
        aclRepository.savePermission(permission);
        log.info("Permission saved: {}", name);
    }

    /**
     * Deletes a permission by name.
     *
     * @param name the permission name
     */
    public void deletePermission(String name) {
        aclRepository.deletePermission(name);
        log.info("Permission deleted: {}", name);
    }
}
