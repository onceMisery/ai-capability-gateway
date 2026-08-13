package com.ai.gateway.domain.model;

/**
 * A named, reusable envelope profile for standardizing unified-response
 * unwrapping configurations across capabilities.
 *
 * <p>Requires that each unified-response structure type be
 * registered as a standard envelope profile and validated against the real
 * Provider during compatibility testing. This avoids the pitfall of copying
 * the generic example's {@code /data} path and numeric success values when
 * the actual platform uses {@code /value} and string success codes.</p>
 *
 * @param name the profile name (e.g., "platform-standard")
 * @param envelopeConfig the envelope configuration
 * @param description a human-readable description of the response structure
 * @since 0.1.0
 */
public record EnvelopeProfile(
        String name,
        EnvelopeConfig envelopeConfig,
        String description
) {

    /**
     * Compact constructor performing null checks.
     *
     * @param name the profile name
     * @param envelopeConfig the envelope configuration
     * @param description the human-readable description
     */
    public EnvelopeProfile {
        java.util.Objects.requireNonNull(name, "name must not be null");
        java.util.Objects.requireNonNull(envelopeConfig, "envelopeConfig must not be null");
        java.util.Objects.requireNonNull(description, "description must not be null");
    }
}
