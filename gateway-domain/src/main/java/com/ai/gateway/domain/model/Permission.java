package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * A permission word following the {@code domain:resource:action} three-segment
 * convention.
 *
 * <p>Permission words are managed through the admin console and used in
 * capability-level authorization. Wildcards ({@code *}) are prohibited in
 * permission names; the wildcard is reserved for the Principal's built-in
 * super-permission.</p>
 *
 * @param name the permission word (e.g., "order:detail:read")
 * @param description a human-readable description
 * @param createdAt the creation timestamp
 * @since 0.1.0
 */
public record Permission(
        String name,
        String description,
        Instant createdAt
) {

    /**
     * Pattern for valid permission names: three lowercase segments separated by colons.
     */
    public static final String NAME_PATTERN = "^[a-z][a-z0-9]*(:[a-z][a-z0-9]*){2}$";

    /**
     * Compact constructor performing null checks and name validation.
     */
    public Permission {
        java.util.Objects.requireNonNull(name, "name must not be null");
        java.util.Objects.requireNonNull(description, "description must not be null");
        java.util.Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "Permission name must match pattern " + NAME_PATTERN + ": " + name);
        }
        if (name.contains("*")) {
            throw new IllegalArgumentException("Permission name must not contain wildcard: " + name);
        }
    }
}
