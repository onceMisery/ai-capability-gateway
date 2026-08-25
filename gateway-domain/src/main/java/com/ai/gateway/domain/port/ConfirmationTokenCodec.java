package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.ConfirmationToken;

import java.time.Instant;

/**
 * 为写操作签发并校验不透明的确认令牌。
 */
public interface ConfirmationTokenCodec {

    ConfirmationToken issue(String operationId, String principalDigest, long orgId,
                            String argumentsDigest, Instant expiresAt);

    ConfirmationToken verify(String token);
}
