package com.ai.gateway.domain.port;

import java.util.Map;

/**
 * Port for issuing authentication tokens (e.g., JWT) for the admin console.
 *
 * <p>Allows the console authentication flow to mint tokens without depending
 * on a specific token implementation (Sa-Token, etc.). The port is a pure
 * abstraction with no framework dependencies.</p>
 *
 * @since 0.1.0
 */
public interface TokenIssuerPort {

    record TokenPair(String accessToken, String refreshToken,
                     long expiresInSeconds, long refreshExpiresInSeconds) {
        public TokenPair {
            java.util.Objects.requireNonNull(accessToken, "accessToken must not be null");
            java.util.Objects.requireNonNull(refreshToken, "refreshToken must not be null");
        }
    }

    /**
     * Issues a signed token for the given subject and claims.
     *
     * @param subject  the subject (login id) to embed in the token
     * @param extraData additional claims (orgId, roles, permissions, ...);
     *                  may be {@code null}
     * @return the signed token string
     */
    default String issueToken(String subject, Map<String, Object> extraData) {
        return issueTokenPair(subject, extraData).accessToken();
    }

    TokenPair issueTokenPair(String subject, Map<String, Object> extraData);

    TokenPair refresh(String refreshToken);

    void revokeToken(String token);
}
