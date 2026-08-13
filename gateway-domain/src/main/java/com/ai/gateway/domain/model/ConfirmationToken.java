package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * A short-lived, single-use confirmation token issued during the Prepare
 * phase of the write-operation two-phase protocol.
 *
 * <p>Specifies that after Prepare completes, the gateway
 * returns a confirmation token that must be:</p>
 *
 * <ul>
 * <li><strong>Single-use</strong> - once consumed by Confirm, it cannot
 * be reused. Repeated Confirm calls return the same operation's
 * current state without creating a new operation.</li>
 * <li><strong>Short-lived</strong> - has an expiration time; if Confirm
 * is not called before {@code expiresAt}, the operation transitions
 * to {@link OperationState#EXPIRED}.</li>
 * <li><strong>Bound</strong> - binds to the {@code operationId},
 * Principal digest, organization, and the arguments digest to
 * prevent substitution attacks.</li>
 * <li><strong>Signed</strong> - carries a server signature that the
 * Confirm phase verifies before proceeding.</li>
 * </ul>
 *
 * <p>During Confirm, the gateway must verify:</p>
 * <ol>
 * <li>The token signature.</li>
 * <li>The token has not expired.</li>
 * <li>The token has not been used.</li>
 * <li>The current Principal matches the Prepare-phase Principal.</li>
 * <li>The operation is still in PREPARED state.</li>
 * <li>The capability has not been suspended and the Manifest digest
 * has not been revoked.</li>
 * </ol>
 *
 * @param token the opaque token string
 * @param operationId the associated operation ID
 * @param principalDigest the digest of the Prepare-phase Principal
 * @param orgId the organization context
 * @param argumentsDigest the digest of the bound arguments
 * @param serverSignature the server-side signature over all bound fields
 * @param expiresAt the token expiration time
 * @param used whether the token has already been consumed
 * @since 0.1.0
 */
public record ConfirmationToken(
        String token,
        String operationId,
        String principalDigest,
        long orgId,
        String argumentsDigest,
        String serverSignature,
        Instant expiresAt,
        boolean used
) {

    /**
     * Compact constructor performing null checks on required fields.
     *
     * @param token the token string
     * @param operationId the operation ID
     * @param principalDigest the principal digest
     * @param orgId the organization ID
     * @param argumentsDigest the arguments digest
     * @param serverSignature the server signature
     * @param expiresAt the expiration time
     * @param used the used flag
     */
    public ConfirmationToken {
        java.util.Objects.requireNonNull(token, "token must not be null");
        java.util.Objects.requireNonNull(operationId, "operationId must not be null");
        java.util.Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        java.util.Objects.requireNonNull(argumentsDigest, "argumentsDigest must not be null");
        java.util.Objects.requireNonNull(serverSignature, "serverSignature must not be null");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
