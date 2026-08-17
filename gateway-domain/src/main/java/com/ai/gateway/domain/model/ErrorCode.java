package com.ai.gateway.domain.model;

/**
 * Stable error codes for the gateway's execution pipeline.
 *
 * <p>Defines the full error taxonomy. External error responses
 * must never contain stack traces, internal addresses, interface class names,
 * or sensitive parameters. The audit log records the stable error code
 * alongside a controlled diagnostic summary.</p>
 *
 * <p>The {@code retryable} flag provides the default retry policy:</p>
 * <ul>
 * <li>{@code false} - The error is terminal; retrying will not help.</li>
 * <li>{@code true} - A retry may succeed, subject to the risk level,
 * idempotency policy, and the specific retry rules described below.</li>
 * </ul>
 *
 * <p>Note: some error codes have nuanced retry semantics that depend on
 * the caller type (user vs. gateway) and the operation's risk level.
 * See the individual constant documentation for details.</p>
 *
 * @see InvocationResult
 * @see AuditEvent
 * @since 0.1.0
 */
public enum ErrorCode {

    /**
     * The caller's identity is invalid or could not be verified.
     * Not retryable.
     */
    AUTHENTICATION_FAILED(false),

    /**
     * The authenticated principal is not authorized to view or execute
     * the requested capability. Not retryable.
     */
    PERMISSION_DENIED(false),

    /** A write capability must use the Prepare/Confirm protocol. */
    CONFIRMATION_REQUIRED(false),

    /**
     * No capability matched the natural-language request after retrieval
     * and threshold filtering. Not retryable.
     */
    NO_CAPABILITY_MATCH(false),

    /**
     * The model or gateway requires the user to provide additional
     * information or disambiguate the request. Retried after user
     * supplementation.
     */
    CLARIFICATION_REQUIRED(true),

    /**
     * The model's structured output failed schema or business validation.
     * The gateway may attempt one automatic repair, after which the error
     * is terminal for that request.
     */
    INVALID_MODEL_OUTPUT(true),

    /**
     * The bound arguments do not satisfy the capability's input contract.
     * Retried after user correction of the parameters.
     */
    ARGUMENT_VALIDATION_FAILED(true),

    /**
     * The selected capability is suspended, retired, or its version is
     * no longer available. Not retryable.
     */
    CAPABILITY_UNAVAILABLE(false),

    /** The configured language-model provider is unreachable or unhealthy. */
    LLM_UNAVAILABLE(true),

    /** A bounded gateway resource rejected the request without queueing. */
    RATE_LIMITED(true),

    /**
     * The Provider timed out. Retryability depends on the risk level and
     * idempotency policy: read-only operations may retry per policy;
     * write operations must follow the two-phase recovery protocol
     */
    PROVIDER_TIMEOUT(true),

    /**
     * The Provider returned a business-level failure (e.g., a non-success
     * envelope code). Typically not retryable, as the business state has
     * already changed or the condition persists.
     */
    PROVIDER_REJECTED(false),

    /**
     * A protocol-level or response-contract error occurred (e.g.,
     * unexpected response structure, missing envelope path). Read-only
     * operations may retry per resilience policy.
     */
    PROTOCOL_ERROR(true),

    /**
     * The response exceeded the configured maximum byte limit
     *. Not retryable.
     */
    RESULT_TOO_LARGE(false),

    /**
     * A write operation's result is uncertain: the request may have
     * reached the Provider, but the gateway did not receive a definitive
     * response. Only resolvable via status query or reconciliation
     */
    EXECUTION_UNKNOWN(false);

    private final boolean retryable;

    ErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    /**
     * Returns whether this error is retryable by default.
     *
     * @return {@code true} if a retry may succeed subject to risk and
     * idempotency policy; {@code false} if the error is terminal
     */
    public boolean isRetryable() {
        return retryable;
    }
}
