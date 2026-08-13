package com.ai.gateway.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * A lightweight summary of a capability manifest for list views.
 *
 * <p>Aggregates manifest metadata with lifecycle state, validation status,
 * and snapshot version history. Does not include sensitive invocation
 * details.</p>
 *
 * @param capabilityId the globally stable capability identifier
 * @param version the semantic version string
 * @param displayName the user-facing capability name
 * @param description the single business-action description
 * @param risk the risk level
 * @param lifecycle the current lifecycle state
 * @param tags the controlled tags
 * @param ownerTeam the responsible team name
 * @param ownerContact the responsible team contact
 * @param sha256Digest the content SHA-256 digest
 * @param updatedAt the last update timestamp
 * @param snapshotVersions the snapshot versions that include this capability
 * @since 0.1.0
 */
public record CapabilitySummary(
        String capabilityId,
        String version,
        String displayName,
        String description,
        RiskLevel risk,
        CapabilityLifecycle lifecycle,
        List<String> tags,
        String ownerTeam,
        String ownerContact,
        String sha256Digest,
        Instant updatedAt,
        List<Long> snapshotVersions
) {

    /**
     * Compact constructor performing defensive copying.
     */
    public CapabilitySummary {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(version, "version must not be null");
        java.util.Objects.requireNonNull(displayName, "displayName must not be null");
        java.util.Objects.requireNonNull(description, "description must not be null");
        java.util.Objects.requireNonNull(risk, "risk must not be null");
        java.util.Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        java.util.Objects.requireNonNull(ownerTeam, "ownerTeam must not be null");
        java.util.Objects.requireNonNull(ownerContact, "ownerContact must not be null");
        java.util.Objects.requireNonNull(sha256Digest, "sha256Digest must not be null");
        java.util.Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        tags = tags == null ? List.of() : List.copyOf(tags);
        snapshotVersions = snapshotVersions == null ? List.of() : List.copyOf(snapshotVersions);
    }
}
