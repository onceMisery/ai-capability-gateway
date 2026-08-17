package com.ai.gateway.domain.model;

/**
 * Lifecycle states for a Capability Manifest.
 *
 * <p>The state machine is:</p>
 * <pre>
 * DRAFT -&gt; VALIDATED -&gt; APPROVED -&gt; PUBLISHED -&gt; SUSPENDED -&gt; RETIRED
 *                 |                              |
 *                 +--&gt; REJECTED                  +--&gt; VALIDATED
 * </pre>
 * <p>Precisely: only VALIDATED may transition to terminal REJECTED, and a
 * SUSPENDED version must be re-validated before approval and publication.</p>
 *
 * <p>Only {@code PUBLISHED} capabilities may enter the candidate set for
 * natural-language routing (Section 9). Write operations must be rejected
 * if the capability is {@code SUSPENDED} or {@code RETIRED} at confirm time
 *.</p>
 *
 * @since 0.1.0
 */
public enum CapabilityLifecycle {
    /**
     * Manifest has been imported and is editable. Not yet validated.
     */
    DRAFT,

    /**
     * Passed all structural, semantic, security, and compatibility
     * validation steps defined in .
     */
    VALIDATED,

    /**
     * Submitter has reviewed the confirmation summary and
     * the batch security review has passed.
     */
    APPROVED,

    /**
     * Entered the active snapshot of the target environment. Only
     * PUBLISHED capabilities participate in routing.
     */
    PUBLISHED,

    /**
     * Temporarily stopped accepting new requests. Audit and recovery
     * capabilities are retained. Restoration requires re-validation
     * and a new snapshot.
     */
    SUSPENDED,

    /**
     * Permanently removed from routing. Historical records are retained.
     */
    RETIRED,

    /**
     * Validation or approval was rejected. This version is terminal;
     * correction requires importing a new manifest version.
     */
    REJECTED
}
