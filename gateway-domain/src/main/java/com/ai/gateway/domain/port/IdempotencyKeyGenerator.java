package com.ai.gateway.domain.port;

/**
 * Port for generating server-side idempotency keys.
 *
 * <p>(Prepare) specifies that the Prepare phase generates a
 * server-side idempotency key and an arguments digest. The idempotency
 * key is persisted in the immutable operation record and is used by
 * recovery tasks to query the Provider status when the gateway does not
 * receive a definitive response.</p>
 *
 * <p>(State Machine): when an operation enters the
 * {@code UNKNOWN} state (the request may have reached the Provider but
 * the gateway did not receive a definitive response), the gateway must
 * not auto-re-execute the natural-language request. Instead, a recovery
 * task uses the same idempotency key to query the Provider status.
 * Unresolvable cases enter {@code MANUAL_REVIEW}.</p>
 *
 * <p>: the database transaction and remote call do not have
 * atomic commit, so the system cannot claim "exactly-once." The system
 * guarantees trackable, queryable, and best-effort de-duplication under
 * the same operation ID and idempotency key.</p>
 *
 * <p>The idempotency key is passed to the Provider via the
 * {@link com.ai.gateway.domain.model.InvocationRequest} and must be
 * deterministic enough for the Provider to recognize duplicate requests.</p>
 *
 * <p>Adapters implementing this port generate idempotency keys using a
 * deterministic or UUID-based strategy. The port is a pure abstraction
 * with no framework dependencies.</p>
 *
 * @see com.ai.gateway.domain.model.OperationRecord
 * @since 0.1.0
 */
public interface IdempotencyKeyGenerator {

    /**
     * Generates an idempotency key for a write operation.
     *
     * <p>: the Prepare phase generates a server-side
     * idempotency key bound to the capability and operation. The key is
     * used by the Confirm phase when calling the Provider
     * and by recovery tasks to query Provider status for
     * UNKNOWN operations.</p>
     *
     * @param capabilityId the capability identifier
     * @param operationId the operation identifier
     * @return the generated idempotency key; never {@code null}
     */
    String generate(String capabilityId, String operationId);
}
