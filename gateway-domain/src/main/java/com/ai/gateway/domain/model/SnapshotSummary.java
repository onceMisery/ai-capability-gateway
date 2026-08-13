package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * A lightweight summary of a catalog snapshot for list views.
 *
 * <p>Used by the admin console to display snapshot history without
 * loading the full snapshot content.</p>
 *
 * @param snapshotVersion the monotonically increasing snapshot version
 * @param environment the target environment (e.g., "production")
 * @param status the snapshot status (ACTIVE or SUPERSEDED)
 * @param digest the SHA-256 digest of the snapshot content
 * @param capabilityCount the number of capabilities in the snapshot
 * @param publishedAt the publication timestamp
 * @param publishedBy the identity that published the snapshot
 * @since 0.1.0
 */
public record SnapshotSummary(
        long snapshotVersion,
        String environment,
        String status,
        String digest,
        int capabilityCount,
        Instant publishedAt,
        String publishedBy
) {

    /**
     * Compact constructor performing null checks.
     */
    public SnapshotSummary {
        java.util.Objects.requireNonNull(environment, "environment must not be null");
        java.util.Objects.requireNonNull(status, "status must not be null");
        java.util.Objects.requireNonNull(digest, "digest must not be null");
        java.util.Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        java.util.Objects.requireNonNull(publishedBy, "publishedBy must not be null");
    }
}
