package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.AuditEvent;

/**
 * Port for persisting audit events with Fail Closed semantics.
 *
 * <p>Specifies that all terminal states must be audited,
 * including:</p>
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
 * <p>The runtime must persist a {@code REQUEST_ACCEPTED} event after
 * minimum identity verification and before retrieval or LLM calls. If
 * persistence fails, the gateway refuses to continue processing (Fail
 * Closed). Before calling the Provider, a {@code STARTED} event must be
 * persisted; after the call, the terminal state must be persisted before
 * returning data to the client. Terminal state persistence failure must
 * not return Provider data.</p>
 *
 * <p>The audit table uses append-only permissions and is exported via an
 * Outbox to a separate SIEM or immutable storage. Since database
 * administrators retain the ability to modify the database, the business
 * table alone cannot claim tamper-proof guarantees.</p>
 *
 * <p>Sensitive parameters are recorded only as redacted summaries or
 * irreversible hashes. Trace attributes record only capability ID,
 * version, snapshot, stable error codes, and durations — not sensitive
 * parameters.</p>
 *
 * <p>Adapters implementing this port persist audit events to PostgreSQL
 * with micro-batching optimizations. The port is a pure
 * abstraction with no framework dependencies.</p>
 *
 * @see AuditEvent
 * @since 0.1.0
 */
public interface AuditPort {

    /**
     * Records a {@code REQUEST_ACCEPTED} audit event.
     *
     * <p>: after minimum identity verification, the runtime
     * must persist this event before retrieval or LLM calls. If
     * persistence fails, the gateway refuses to continue processing
     * (Fail Closed).</p>
     *
     * @param requestId the unique request identifier
     * @param subjectDigest the digest of the caller's subject identity
     * @param orgId the organization context
     */
    void recordAccepted(String requestId, String subjectDigest, long orgId);

    /**
     * Records a {@code STARTED} audit event before calling the Provider.
     *
     * <p>: before calling the Provider, a {@code STARTED}
     * event must be persisted. After the call, the terminal state must
     * be persisted before returning data to the client. Terminal state
     * persistence failure must not return Provider data.</p>
     *
     * @param requestId the unique request identifier
     * @param capabilityId the capability identifier
     * @param capabilityVersion the capability semantic version
     * @param manifestDigest the SHA-256 digest of the invoked Manifest
     */
    void recordStarted(String requestId, String capabilityId, String capabilityVersion, String manifestDigest);

    /**
     * Records a terminal audit event after the Provider call completes.
     *
     * <p>: the terminal state must be persisted before
     * returning data to the client. Sensitive parameters are recorded
     * only as redacted summaries or irreversible hashes.</p>
     *
     * @param requestId the unique request identifier
     * @param capabilityId the capability identifier
     * @param capabilityVersion the capability semantic version
     * @param resultCode the stable result code (e.g., an {@link com.ai.gateway.domain.model.ErrorCode} name)
     * @param durationMs the call duration in milliseconds
     * @param detailsJson the controlled diagnostic summary as JSON;
     * never contains stacks, internal addresses,
     * or sensitive params
     */
    void recordTerminal(String requestId, String capabilityId, String capabilityVersion,
                        String resultCode, long durationMs, String detailsJson);

    /**
     * Records a custom audit event.
     *
     * <p>: used for events not covered by the convenience
     * methods above, such as authentication failures, no-match,
     * clarification, permission denials, manifest lifecycle changes, and
     * write-operation state transitions.</p>
     *
     * @param event the audit event to persist; never {@code null}
     */
    void recordEvent(AuditEvent event);
}
