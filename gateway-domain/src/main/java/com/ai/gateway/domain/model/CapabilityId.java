package com.ai.gateway.domain.model;

import java.util.regex.Pattern;

/**
 * Globally stable capability identifier.
 *
 * <p>Specifies that {@code metadata.id} should use the
 * {@code domain.resource.action} naming convention (e.g.,
 * {@code order.detail.query}). The identifier must only contain lowercase
 * letters, digits, dots ({@code .}), and hyphens ({@code -}). Once published,
 * the ID cannot be renamed.</p>
 *
 * <p>This is a value object: two {@code CapabilityId} instances with the same
 * {@code value} are considered equal.</p>
 *
 * @param value the capability identifier string (e.g., {@code "order.detail.query"})
 * @since 0.1.0
 */
public record CapabilityId(String value) {

    /**
     * Pattern that validates capability identifiers: lowercase letters,
     * digits, dots, and hyphens only.
     */
    private static final Pattern VALID_PATTERN =
            Pattern.compile("^[a-z0-9.\\-]+$");

    /**
     * Compact constructor performing format validation.
     *
     * @param value the capability identifier string
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is blank or contains
     * characters outside the allowed set
     */
    public CapabilityId {
        java.util.Objects.requireNonNull(value, "capabilityId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("capabilityId value must not be blank");
        }
        if (!VALID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "capabilityId value must contain only lowercase letters, digits, dots, and hyphens: " + value);
        }
    }
}
