package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;

import java.util.Optional;

/**
 * Port for persisting write-operation records.
 *
 * <p>(Prepare) specifies that the Prepare phase must persist
 * an immutable operation record containing at minimum: operationId,
 * state = PREPARED, principalDigest + orgId, capabilityId + version +
 * manifestDigest, snapshotVersion, encryptedArguments, argumentsDigest,
 * idempotencyKey, policyDecisionId, confirmationSummary, and expiresAt.</p>
 *
 * <p>(Confirm) uses a conditional database update on version
 * to atomically claim execution (optimistic concurrency control). The
 * {@code version} field supports CAS (Compare-And-Swap) state transitions
 * to prevent duplicate execution.</p>
 *
 * <p>(State Machine): the operation state transitions are
 * PREPARED -> EXECUTING -> SUCCEEDED / FAILED / UNKNOWN, with EXPIRED,
 * CANCELLED, and MANUAL_REVIEW terminal states. {@code UNKNOWN} means
 * the request may have reached the Provider but the gateway did not
 * receive a definitive response; the gateway must not auto-re-execute
 * and must use recovery tasks with the idempotency key.</p>
 *
 * <p>Adapters implementing this port persist operation records in
 * PostgreSQL with version-based optimistic concurrency. The port is a
 * pure abstraction with no framework dependencies.</p>
 *
 * @see OperationRecord
 * @see OperationState
 * @since 0.1.0
 */
public interface OperationRepository {

    /**
     * Persists an operation record during the Prepare phase.
     *
     * <p>: the Prepare phase persists an immutable operation
     * record with state = PREPARED, encrypted arguments, arguments digest,
     * idempotency key, and confirmation summary. A short-lived
     * confirmation token is issued to the user.</p>
     *
     * @param record the operation record to persist; never {@code null}
     */
    void save(OperationRecord record);

    /**
     * Finds an operation record by its operation ID.
     *
     * <p>Used by the Confirm phase to verify the operation
     * is still PREPARED and to check capability suspension status before
     * claiming execution.</p>
     *
     * @param operationId the unique operation identifier
     * @return the operation record, or empty if not found
     */
    Optional<OperationRecord> findById(String operationId);

    /**
     * Atomically transitions the operation state using Compare-And-Swap
     * (CAS) on the expected version.
     *
     * <p>: the Confirm phase uses a conditional database update
     * on version to atomically claim execution. This prevents duplicate
     * execution when multiple confirm attempts race. Returns {@code false}
     * if the current version does not match {@code expectedVersion} or
     * the current state does not match {@code expectedState}.</p>
     *
     * <p>: valid state transitions are governed by the state
     * machine. Invalid transitions must return {@code false}.</p>
     *
     * @param operationId the operation identifier
     * @param expectedState the expected current state
     * @param newState the target state to transition to
     * @param expectedVersion the expected optimistic concurrency version
     * @return {@code true} if the CAS update succeeded; {@code false} otherwise
     */
    boolean casUpdateState(String operationId, OperationState expectedState,
                           OperationState newState, long expectedVersion);
}
