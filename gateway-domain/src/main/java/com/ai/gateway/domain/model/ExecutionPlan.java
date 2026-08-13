package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * The immutable execution plan generated after model routing succeeds.
 *
 * <p>Specifies that after the model selects a capability and
 * the gateway validates arguments, it generates an immutable ExecutionPlan
 * containing:</p>
 *
 * <pre>
 * executionId
 * principalDigest
 * snapshotVersion
 * capabilityId + capabilityVersion + manifestDigest
 * validatedModelArguments
 * resolvedProtocolArguments
 * policyDecisionId
 * risk
 * timeout/retry/idempotency policy
 * </pre>
 *
 * <p>The execution plan must not contain any protocol configuration that
 * could be modified by the model. Read-only operations may execute
 * immediately; write operations must enter the PREPARED state of the
 * two-phase protocol (Section 13).</p>
 *
 * <p>The {@code validatedModelArguments} are the model-generated arguments
 * that passed Schema and business validation. The
 * {@code resolvedProtocolArguments} are the fully bound arguments with
 * PRINCIPAL, CONSTANT, and SYSTEM values injected — they exist only in
 * execution memory and must not be logged in plaintext.</p>
 *
 * @param executionId the unique execution identifier
 * @param principalDigest the digest of the executing Principal
 * @param snapshotVersion the catalog snapshot version at routing time
 * @param capabilityId the capability identifier
 * @param capabilityVersion the capability semantic version
 * @param manifestDigest the SHA-256 digest of the invoked Manifest
 * @param validatedModelArguments the validated model-generated arguments
 * @param resolvedProtocolArguments the fully bound, positionally-ordered arguments
 * @param policyDecisionId the authorization policy decision ID
 * @param risk the capability risk level
 * @param resiliencePolicy the timeout, retry, and concurrency policy
 * @since 0.1.0
 */
public record ExecutionPlan(
        String executionId,
        String principalDigest,
        long snapshotVersion,
        String capabilityId,
        String capabilityVersion,
        String manifestDigest,
        Map<String, Object> validatedModelArguments,
        java.util.List<Object> resolvedProtocolArguments,
        String policyDecisionId,
        RiskLevel risk,
        ResiliencePolicy resiliencePolicy
) {

    /**
     * Compact constructor performing defensive copying and null checks.
     *
     * @param executionId the execution ID
     * @param principalDigest the principal digest
     * @param snapshotVersion the snapshot version
     * @param capabilityId the capability ID
     * @param capabilityVersion the capability version
     * @param manifestDigest the manifest digest
     * @param validatedModelArguments the validated model arguments
     * @param resolvedProtocolArguments the resolved protocol arguments
     * @param policyDecisionId the policy decision ID
     * @param risk the risk level
     * @param resiliencePolicy the resilience policy
     */
    public ExecutionPlan {
        java.util.Objects.requireNonNull(executionId, "executionId must not be null");
        java.util.Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(capabilityVersion, "capabilityVersion must not be null");
        java.util.Objects.requireNonNull(manifestDigest, "manifestDigest must not be null");
        java.util.Objects.requireNonNull(validatedModelArguments, "validatedModelArguments must not be null");
        java.util.Objects.requireNonNull(resolvedProtocolArguments, "resolvedProtocolArguments must not be null");
        java.util.Objects.requireNonNull(risk, "risk must not be null");
        java.util.Objects.requireNonNull(resiliencePolicy, "resiliencePolicy must not be null");
        validatedModelArguments = Map.copyOf(validatedModelArguments);
        resolvedProtocolArguments = java.util.List.copyOf(resolvedProtocolArguments);
    }
}
