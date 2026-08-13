package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * The immutable operation record persisted during the Prepare phase of the
 * write-operation two-phase protocol.
 *
 * <p>Specifies that the Prepare phase must persist an immutable
 * operation record containing at minimum:</p>
 *
 * <pre>
 * operationId
 * state = PREPARED
 * principalDigest + orgId
 * capabilityId + version + manifestDigest
 * snapshotVersion
 * encryptedArguments
 * argumentsDigest
 * idempotencyKey
 * policyDecisionId
 * confirmationSummary
 * expiresAt
 * </pre>
 *
 * <p>The confirmation token issued to the user must be single-use,
 * short-lived, and bound to {@code operationId}, {@code principalDigest},
 * {@code orgId}, {@code argumentsDigest}, and a server signature
 *. The {@code encryptedArguments} field stores the bound
 * parameters encrypted at rest; {@code argumentsDigest} allows integrity
 * verification without decryption.</p>
 *
 * <p>The {@code version} field supports optimistic concurrency control:
 * the Confirm phase uses a conditional database update on version to
 * atomically claim execution.</p>
 *
 * @param operationId the unique operation identifier
 * @param state the current operation state
 * @param principalDigest the digest of the Prepare-phase Principal
 * @param orgId the organization context
 * @param capabilityId the capability identifier
 * @param capabilityVersion the capability semantic version
 * @param manifestDigest the SHA-256 digest of the invoked Manifest
 * @param snapshotVersion the catalog snapshot version at Prepare time
 * @param encryptedArguments the encrypted bound arguments
 * @param argumentsDigest the digest of the bound arguments
 * @param idempotencyKey the server-generated idempotency key
 * @param policyDecisionId the authorization policy decision ID
 * @param confirmationSummary the confirmation summary shown to the user
 * @param expiresAt the operation expiration time
 * @param version the optimistic concurrency version
 * @since 0.1.0
 */
public record OperationRecord(
        String operationId,
        OperationState state,
        String principalDigest,
        long orgId,
        String capabilityId,
        String capabilityVersion,
        String manifestDigest,
        long snapshotVersion,
        String encryptedArguments,
        String argumentsDigest,
        String idempotencyKey,
        String policyDecisionId,
        ConfirmationSummary confirmationSummary,
        Instant expiresAt,
        long version
) {

    /**
     * Compact constructor performing null checks on required fields.
     *
     * @param operationId the operation ID
     * @param state the operation state
     * @param principalDigest the principal digest
     * @param orgId the org ID
     * @param capabilityId the capability ID
     * @param capabilityVersion the capability version
     * @param manifestDigest the manifest digest
     * @param snapshotVersion the snapshot version
     * @param encryptedArguments the encrypted arguments
     * @param argumentsDigest the arguments digest
     * @param idempotencyKey the idempotency key
     * @param policyDecisionId the policy decision ID
     * @param confirmationSummary the confirmation summary
     * @param expiresAt the expiration time
     * @param version the optimistic version
     */
    public OperationRecord {
        java.util.Objects.requireNonNull(operationId, "operationId must not be null");
        java.util.Objects.requireNonNull(state, "state must not be null");
        java.util.Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(capabilityVersion, "capabilityVersion must not be null");
        java.util.Objects.requireNonNull(manifestDigest, "manifestDigest must not be null");
        java.util.Objects.requireNonNull(encryptedArguments, "encryptedArguments must not be null");
        java.util.Objects.requireNonNull(argumentsDigest, "argumentsDigest must not be null");
        java.util.Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
