package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * An append-only audit event recording a terminal or significant state
 * transition in the gateway.
 *
 * <p>Specifies that all terminal states must be audited,
 * including:</p>
 *
 * <ul>
 * <li>Authentication failures.</li>
 * <li>No-match and clarification events.</li>
 * <li>Permission denials.</li>
 * <li>Invalid model output.</li>
 * <li>Argument validation failures.</li>
 * <li>Call success, business failure, protocol error, and timeout.</li>
 * <li>Write-operation Prepare, Confirm, Cancel, Expire, Unknown, and
 * recovery events.</li>
 * <li>Manifest import, confirmation, publication, suspension, and
 * rollback events.</li>
 * </ul>
 *
 * <p>The audit table uses append-only permissions and is exported via an
 * Outbox to a separate SIEM or immutable storage. Since database
 * administrators retain the ability to modify the database, the business
 * table alone cannot claim tamper-proof guarantees.</p>
 *
 * <p>The runtime must persist a {@code REQUEST_ACCEPTED} event after minimum
 * identity verification and before retrieval or LLM calls. If persistence
 * fails, the gateway refuses to continue processing. Before calling the
 * Provider, a {@code STARTED} event must be persisted; after the call, the
 * terminal state must be persisted before returning data to the client
 *.</p>
 *
 * <p>Sensitive parameters are recorded only as redacted summaries or
 * irreversible hashes. Trace attributes record only capability ID, version,
 * snapshot, stable error codes, and durations — not sensitive parameters
 *.</p>
 *
 * @param eventId the unique event identifier
 * @param eventType the event type (e.g., "REQUEST_ACCEPTED", "STARTED", "SUCCEEDED")
 * @param timestamp the event timestamp
 * @param subjectDigest the digest of the caller's subject identity
 * @param orgId the organization context
 * @param requestId the request identifier
 * @param operationId the operation identifier; null for read-only
 * @param capabilityId the capability identifier; null if not applicable
 * @param capabilityVersion the capability version; null if not applicable
 * @param manifestDigest the Manifest SHA-256 digest; null if not applicable
 * @param snapshotVersion the catalog snapshot version
 * @param policyDecisionId the authorization policy decision ID
 * @param modelPromptVersion the model/prompt version used for routing
 * @param resultCode the stable result code (e.g., an {@link ErrorCode} name)
 * @param durationMs the event duration in milliseconds
 * @param detailsJson the controlled diagnostic summary as JSON; never
 * contains stacks, internal addresses, or sensitive params
 * @since 0.1.0
 */
public record AuditEvent(
        String eventId,
        String eventType,
        Instant timestamp,
        String subjectDigest,
        long orgId,
        String requestId,
        String operationId,
        String capabilityId,
        String capabilityVersion,
        String manifestDigest,
        long snapshotVersion,
        String policyDecisionId,
        String modelPromptVersion,
        String resultCode,
        long durationMs,
        String detailsJson
) {

    /**
     * Compact constructor performing null checks on required fields.
     *
     * @param eventId the event ID
     * @param eventType the event type
     * @param timestamp the timestamp
     * @param subjectDigest the subject digest
     * @param orgId the org ID
     * @param requestId the request ID
     * @param operationId the operation ID
     * @param capabilityId the capability ID
     * @param capabilityVersion the capability version
     * @param manifestDigest the manifest digest
     * @param snapshotVersion the snapshot version
     * @param policyDecisionId the policy decision ID
     * @param modelPromptVersion the model prompt version
     * @param resultCode the result code
     * @param durationMs the duration in milliseconds
     * @param detailsJson the details JSON
     */
    public AuditEvent {
        java.util.Objects.requireNonNull(eventId, "eventId must not be null");
        java.util.Objects.requireNonNull(eventType, "eventType must not be null");
        java.util.Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}
