package com.ai.gateway.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * A named role that groups a set of permissions for authorization decisions.
 *
 * <p>Roles are managed through the admin console and persisted in PostgreSQL.
 * Each role carries a set of permission words that determine what capabilities
 * and administrative actions the role can access.</p>
 *
 * @param name the unique role name
 * @param description a human-readable description
 * @param permissions the permission words assigned to this role
 * @param createdAt the creation timestamp
 * @param updatedAt the last update timestamp
 * @since 0.1.0
 */
public record Role(
        String name,
        String description,
        List<String> permissions,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Compact constructor performing defensive copying.
     */
    public Role {
        java.util.Objects.requireNonNull(name, "name must not be null");
        java.util.Objects.requireNonNull(description, "description must not be null");
        java.util.Objects.requireNonNull(createdAt, "createdAt must not be null");
        java.util.Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
