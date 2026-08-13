package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ConfirmationSummary;
import com.ai.gateway.domain.model.ValidationReport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for persisting and retrieving Capability Manifests throughout the
 * control-plane lifecycle.
 *
 * <p>Defines the lifecycle state machine:
 * {@code DRAFT -> VALIDATED -> APPROVED -> PUBLISHED -> SUSPENDED -> RETIRED}.
 * Only {@code PUBLISHED} capabilities may enter the candidate set for
 * natural-language routing (Section 9).</p>
 *
 * <p>Defines the 10-step validation pipeline for Manifest
 * import. The same {@code id + version} content cannot be overwritten;
 * modifications must produce a new version. Each manifest
 * has a content SHA-256 digest that binds the exact published content.</p>
 *
 * <p>Adapters implementing this port typically persist manifests in
 * PostgreSQL. The port is a pure abstraction with no framework
 * dependencies.</p>
 *
 * @see CapabilityManifest
 * @see CapabilityLifecycle
 * @since 0.1.0
 */
public interface ManifestRepository {

    /**
     * Persists a Capability Manifest along with its content SHA-256 digest.
     *
     * <p>step 10 generates the content SHA-256 digest. The
     * digest binds the exact published content and is used by instances
     * to verify snapshot integrity.</p>
     *
     * @param manifest the capability manifest to persist
     * @param sha256Digest the SHA-256 content digest of the manifest
     */
    void save(CapabilityManifest manifest, String sha256Digest);

    /**
     * Finds a Capability Manifest by its ID and version.
     *
     * <p>The {@code id} uses the {@code domain.resource.action} convention
     * and {@code version} is a SemVer string. The same
     * {@code id + version} content cannot be overwritten.</p>
     *
     * @param id the globally stable capability identifier
     * @param version the semantic version string
     * @return the matching manifest, or empty if not found
     */
    Optional<CapabilityManifest> findByIdAndVersion(String id, String version);

    /**
     * Returns all persisted Capability Manifests.
     *
     * <p>Used by the control plane for batch operations such as batch
     * confirmation and compatibility analysis with active
     * versions.</p>
     *
     * @return an unmodifiable list of all manifests; never {@code null}
     */
    List<CapabilityManifest> findAll();

    /**
     * Returns all persisted Capability Manifests along with their database
     * metadata (lifecycle, SHA-256 digest, and last-updated timestamp).
     *
     * <p>Used by the admin console to display capability summaries with
     * accurate lifecycle state, content digest, and update time.</p>
     *
     * @return an unmodifiable list of manifest details; never {@code null}
     */
    List<ManifestDetail> findAllWithDetails();

    /**
     * Rich result combining a CapabilityManifest with its database-level
     * metadata columns.
     *
     * @param manifest the deserialized capability manifest
     * @param lifecycle the current lifecycle state from the database
     * @param sha256Digest the content SHA-256 digest from the database
     * @param updatedAt the last-updated timestamp from the database
     */
    record ManifestDetail(
            CapabilityManifest manifest,
            CapabilityLifecycle lifecycle,
            String sha256Digest,
            Instant updatedAt
    ) {}

    /**
     * Updates the lifecycle state of a specific capability manifest.
     *
     * <p>: the lifecycle transitions are governed by the state
     * machine. Only {@code PUBLISHED} capabilities may enter the candidate
     * set. Write operations must be rejected if the capability is
     * {@code SUSPENDED} or {@code RETIRED} at confirm time.</p>
     *
     * @param id the capability identifier
     * @param version the semantic version
     * @param lifecycle the new lifecycle state to transition to
     */
    void updateLifecycle(String id, String version, CapabilityLifecycle lifecycle);

    /** Persists the latest validation report and transitions a valid manifest. */
    void recordValidation(String id, String version, ValidationReport report);

    /** Persists an approval decision and applies its lifecycle transition. */
    void recordApproval(String id, String version, String approver,
                        String decision, ConfirmationSummary summary);
}
