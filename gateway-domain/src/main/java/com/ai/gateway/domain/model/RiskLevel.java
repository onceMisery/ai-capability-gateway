package com.ai.gateway.domain.model;

/**
 * Classifies the risk level of a capability, governing the execution mode
 * and the controls that must be applied before invocation.
 *
 * <ul>
 * <li>{@code READ_ONLY} - Pure query; no side effects on Provider state.</li>
 * <li>{@code WRITE_LOW} - Write operation requiring the two-phase Prepare/Confirm
 * protocol defined in Section 13.</li>
 * <li>{@code WRITE_HIGH} - High-impact write; disabled by default and only
 * enabled after independent security review and dual approval.</li>
 * </ul>
 *
 * <p>The risk level is orthogonal to authorization permissions:
 * {@code spec.authorization.permissions} determines <em>whether</em> a principal
 * may call the capability, while {@code spec.risk} determines <em>how strictly</em>
 * the invocation is governed.</p>
 *
 * @see CapabilityManifest
 * @see ExecutionPlan
 * @see ConfirmationSummary
 *
 * @since 0.1.0
 */
public enum RiskLevel {
    /**
     * Read-only query. May be executed immediately after routing and
     * parameter validation without entering the two-phase protocol.
     */
    READ_ONLY,

    /**
     * Low-risk write operation. Requires the Prepare/Confirm protocol
     * (Section 13) with idempotency, timeout, and uncertain-state recovery.
     */
    WRITE_LOW,

    /**
     * High-risk write operation. Disabled in the initial release; only
     * enabled after independent security review and dual approval mechanisms
     * are in place.
     */
    WRITE_HIGH
}
