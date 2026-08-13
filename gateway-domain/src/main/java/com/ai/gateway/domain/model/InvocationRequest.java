package com.ai.gateway.domain.model;

import java.util.List;

/**
 * A protocol-neutral invocation request passed to the {@code InvocationAdapter}
 *
 * <p>The neutral request contains the capability identity, deadline budget,
 * idempotency key, trace context, and the fully-bound, positionally-ordered
 * protocol arguments. The adapter must not perform natural-language routing,
 * user authorization, or capability state changes.</p>
 *
 * <p>The {@code manifestDigest} allows the adapter and audit layer to verify
 * that the invocation is being performed against the exact Manifest content
 * that was published in the snapshot. The {@code boundArguments} are the
 * fully resolved, non-model-injected protocol parameters — they exist only
 * in execution memory and must not be logged in plaintext.</p>
 *
 * @param capabilityId the capability identifier
 * @param capabilityVersion the semantic version
 * @param manifestDigest the SHA-256 digest of the invoked Manifest content
 * @param deadlineBudget the remaining deadline budget for this invocation
 * @param idempotencyKey the server-generated idempotency key; may be null
 * for read-only requests
 * @param systemContext the platform execution context (trace, locale, etc.)
 * @param boundArguments the ordered, fully-bound protocol arguments
 * @since 0.1.0
 */
public record InvocationRequest(
        String capabilityId,
        String capabilityVersion,
        String manifestDigest,
        DeadlineBudget deadlineBudget,
        String idempotencyKey,
        SystemContext systemContext,
        List<Object> boundArguments
) {

    /**
     * Compact constructor performing defensive copying and null checks.
     *
     * @param capabilityId the capability ID
     * @param capabilityVersion the capability version
     * @param manifestDigest the manifest digest
     * @param deadlineBudget the deadline budget
     * @param idempotencyKey the idempotency key
     * @param systemContext the system context
     * @param boundArguments the bound arguments
     */
    public InvocationRequest {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(capabilityVersion, "capabilityVersion must not be null");
        java.util.Objects.requireNonNull(manifestDigest, "manifestDigest must not be null");
        java.util.Objects.requireNonNull(deadlineBudget, "deadlineBudget must not be null");
        java.util.Objects.requireNonNull(systemContext, "systemContext must not be null");
        java.util.Objects.requireNonNull(boundArguments, "boundArguments must not be null");
        boundArguments = List.copyOf(boundArguments);
    }
}
