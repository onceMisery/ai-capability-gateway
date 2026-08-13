package com.ai.gateway.domain.model;

/**
 * State of a write operation in the two-phase Prepare/Confirm protocol
 *
 * <p>The state machine is:</p>
 * <pre>
 * PREPARED -&gt; EXECUTING -&gt; SUCCEEDED
 * | |----&gt; FAILED
 * | +----&gt; UNKNOWN -&gt; SUCCEEDED / FAILED / MANUAL_REVIEW
 * |-----------------&gt; EXPIRED
 * +-----------------&gt; CANCELLED
 * </pre>
 *
 * <p>{@code UNKNOWN} means the request may have reached the Provider, but
 * the gateway did not receive a definitive response. The gateway must not
 * automatically re-execute the natural-language request; instead, a
 * recovery task uses the idempotency key to query the Provider status
 *. Unresolvable cases enter {@code MANUAL_REVIEW}.</p>
 *
 * @see OperationRecord
 * @since 0.1.0
 */
public enum OperationState {
    /**
     * The Prepare phase has completed: parameters are
     * bound, authorization checked, and an immutable operation record
     * persisted. A short-lived confirmation token has been issued.
     */
    PREPARED,

    /**
     * The Confirm phase has atomically claimed execution
     * and the Provider call is in progress.
     */
    EXECUTING,

    /**
     * The Provider call returned a definitive success.
     */
    SUCCEEDED,

    /**
     * The Provider call returned a definitive failure.
     */
    FAILED,

    /**
     * The request may have reached the Provider, but the gateway did not
     * receive a definitive response. Recovery tasks must query the Provider
     * using the idempotency key; auto-re-execution is prohibited.
     */
    UNKNOWN,

    /**
     * The confirmation token expired before Confirm was called. The
     * operation can no longer be confirmed.
     */
    EXPIRED,

    /**
     * The operation was explicitly cancelled before execution.
     */
    CANCELLED,

    /**
     * The recovery task could not automatically determine the final
     * state. Manual intervention is required; an alert is raised.
     */
    MANUAL_REVIEW
}
