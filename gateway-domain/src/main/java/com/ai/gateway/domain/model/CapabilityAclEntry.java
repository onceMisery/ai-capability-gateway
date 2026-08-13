package com.ai.gateway.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * An access-control list entry mapping a capability to the set of roles
 * allowed to invoke it.
 *
 * <p>Used by the admin console to configure capability-level authorization.
 * The entry aggregates all allowed roles for a given capability (across
 * versions) into a single view.</p>
 *
 * @param capabilityId the capability identifier
 * @param capabilityVersion the capability version
 * @param allowedRoles the roles allowed to execute this capability
 * @param requiredPermissions the permissions declared in the Manifest's
 *                            spec.authorization.permissions
 * @param updatedAt the last update timestamp
 * @param updatedBy the identity that last updated the entry
 * @since 0.1.0
 */
public record CapabilityAclEntry(
        String capabilityId,
        String capabilityVersion,
        List<String> allowedRoles,
        List<String> requiredPermissions,
        Instant updatedAt,
        String updatedBy
) {

    /**
     * Compact constructor performing defensive copying.
     */
    public CapabilityAclEntry {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(capabilityVersion, "capabilityVersion must not be null");
        java.util.Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        java.util.Objects.requireNonNull(updatedBy, "updatedBy must not be null");
        allowedRoles = allowedRoles == null ? List.of() : List.copyOf(allowedRoles);
        requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);
    }
}
