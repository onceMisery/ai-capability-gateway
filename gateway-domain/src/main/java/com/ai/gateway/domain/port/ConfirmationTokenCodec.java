package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.ConfirmationToken;

import java.time.Instant;

/**
 * Issues and verifies opaque confirmation tokens for write operations.
 */
public interface ConfirmationTokenCodec {

    ConfirmationToken issue(String operationId, String principalDigest, long orgId,
                            String argumentsDigest, Instant expiresAt);

    ConfirmationToken verify(String token);
}
