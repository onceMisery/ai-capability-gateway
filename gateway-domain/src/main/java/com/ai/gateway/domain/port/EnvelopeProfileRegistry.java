package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.EnvelopeProfile;

import java.util.Optional;

/**
 * Port for the envelope profile registry.
 *
 * <p>(Response Contract) specifies that the gateway cannot
 * depend on business unified-response Java JARs, so it uses structured
 * path-based envelope unwrapping. Each unified-response structure type
 * must be registered as a standard envelope profile and validated against
 * the real Provider during compatibility testing.</p>
 *
 * <p>This avoids the pitfall of copying the generic example's
 * {@code /data} path and numeric success values when the actual platform
 * uses {@code /value} and string success codes. Envelope profiles are
 * named, reusable configurations for standardizing unified-response
 * unwrapping across capabilities.</p>
 *
 * <p>Adapters implementing this port manage the lifecycle of envelope
 * profiles (typically persisted in a configuration store). The port is a
 * pure abstraction with no framework dependencies.</p>
 *
 * @see EnvelopeProfile
 * @since 0.1.0
 */
public interface EnvelopeProfileRegistry {

    /**
     * Finds an envelope profile by its name.
     *
     * <p>: envelope profiles are referenced by name in
     * capability manifests. The profile defines the envelope unwrapping
     * configuration (code path, success values, data path, message path)
     * validated against the real Provider.</p>
     *
     * @param name the profile name (e.g., "platform-standard")
     * @return the matching envelope profile, or empty if not found
     */
    Optional<EnvelopeProfile> findByName(String name);

    /**
     * Registers or updates an envelope profile.
     *
     * <p>: profiles must be validated against the real Provider
     * during compatibility testing before registration.
     * Registration makes the profile available for reference by capability
     * manifests.</p>
     *
     * @param profile the envelope profile to register; never {@code null}
     */
    void register(EnvelopeProfile profile);
}
