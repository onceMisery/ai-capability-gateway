package com.ai.gateway.domain.model;

import java.util.List;

/**
 * An immutable snapshot of the active capability catalog for a given
 * environment, published atomically by the control plane.
 *
 * <p>Specifies that publication must complete in a single
 * database transaction:</p>
 * <ol>
 * <li>Verify the target version is still APPROVED.</li>
 * <li>Generate a new monotonically increasing {@code snapshotVersion}.</li>
 * <li>Freeze all active capabilities and policy references for this
 * environment.</li>
 * <li>Mark the new snapshot as the current version.</li>
 * <li>Write publication audit and notification events.</li>
 * </ol>
 *
 * <p>Instances receive the notification, load the snapshot from PostgreSQL,
 * build the retrieval index, and verify the digest. On success, the in-memory
 * reference is atomically replaced. On failure, the instance retains the old
 * snapshot and exits the ready state after exceeding the maximum lag time
 *.</p>
 *
 * <p>Each request is pinned to the snapshot version active at the start of
 * processing. Rollback copies a historical snapshot's content
 * into a new snapshot version; it does not modify history.</p>
 *
 * <p>The {@code policyRef} references the authorization policy version
 * active at publication time. The {@code digest} is the content SHA-256
 * digest used by instances to verify snapshot integrity after loading.</p>
 *
 * @param snapshotVersion the monotonically increasing snapshot version
 * @param environment the target environment (e.g., "production")
 * @param capabilities the list of published capability manifests
 * @param policyRef the authorization policy reference
 * @param digest the content SHA-256 digest
 * @since 0.1.0
 */
public record CatalogSnapshot(
        long snapshotVersion,
        String environment,
        List<CapabilityManifest> capabilities,
        String policyRef,
        String digest
) {

    /**
     * Compact constructor performing defensive copying and null checks.
     *
     * @param snapshotVersion the snapshot version
     * @param environment the environment
     * @param capabilities the capability manifests
     * @param policyRef the policy reference
     * @param digest the digest
     */
    public CatalogSnapshot {
        java.util.Objects.requireNonNull(environment, "environment must not be null");
        java.util.Objects.requireNonNull(capabilities, "capabilities must not be null");
        java.util.Objects.requireNonNull(digest, "digest must not be null");
        capabilities = List.copyOf(capabilities);
    }
}
