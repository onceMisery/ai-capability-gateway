package com.ai.gateway.adapter.auth.satoken;

import java.util.Objects;

/**
 * Configuration properties for the Sa-Token authentication adapter.
 *
 * <p>Bound from the {@code gateway.auth.sa-token.*} namespace. These values
 * drive JWT verification (secret key, login type) and token resolution
 * (token name across header / cookie / query parameter sources).</p>
 *
 * <p>This is a plain holder — it deliberately avoids Spring Boot
 * {@code @ConfigurationProperties} so the adapter module stays free of the
 * Spring Boot auto-configuration machinery, consistent with the
 * tech-selection decision to introduce {@code sa-token-core} only.</p>
 *
 * @since 0.1.0
 */
public class SaTokenAuthProperties {

    /**
     * Default token name used to locate the credential in headers, cookies,
     * and query parameters.
     */
    public static final String DEFAULT_TOKEN_NAME = "Authorization";

    /**
     * Default Sa-Token login type embedded in issued JWTs.
     */
    public static final String DEFAULT_LOGIN_TYPE = "login";

    /**
     * Default access-token timeout in seconds (2 hours).
     */
    public static final long DEFAULT_ACCESS_TOKEN_TIMEOUT_SECONDS = 7200L;

    /**
     * Default refresh-token timeout in seconds (7 days).
     */
    public static final long DEFAULT_REFRESH_TOKEN_TIMEOUT_SECONDS = 604800L;

    private String tokenName = DEFAULT_TOKEN_NAME;
    private String loginType = DEFAULT_LOGIN_TYPE;
    private String jwtSecretKey;
    private long accessTokenTimeoutSeconds = DEFAULT_ACCESS_TOKEN_TIMEOUT_SECONDS;
    private long refreshTokenTimeoutSeconds = DEFAULT_REFRESH_TOKEN_TIMEOUT_SECONDS;

    public String getTokenName() {
        return tokenName;
    }

    public void setTokenName(String tokenName) {
        this.tokenName = tokenName;
    }

    public String getLoginType() {
        return loginType;
    }

    public void setLoginType(String loginType) {
        this.loginType = loginType;
    }

    public String getJwtSecretKey() {
        return jwtSecretKey;
    }

    public void setJwtSecretKey(String jwtSecretKey) {
        this.jwtSecretKey = jwtSecretKey;
    }

    public long getAccessTokenTimeoutSeconds() {
        return accessTokenTimeoutSeconds;
    }

    public void setAccessTokenTimeoutSeconds(long accessTokenTimeoutSeconds) {
        this.accessTokenTimeoutSeconds = accessTokenTimeoutSeconds;
    }

    public long getRefreshTokenTimeoutSeconds() {
        return refreshTokenTimeoutSeconds;
    }

    public void setRefreshTokenTimeoutSeconds(long refreshTokenTimeoutSeconds) {
        this.refreshTokenTimeoutSeconds = refreshTokenTimeoutSeconds;
    }

    /**
     * Validates that the minimum required configuration is present.
     *
     * @throws IllegalStateException if the JWT secret key is not configured
     */
    public void validate() {
        Objects.requireNonNull(jwtSecretKey,
                "gateway.auth.sa-token.jwt-secret-key must be configured");
        if (jwtSecretKey.isBlank()) {
            throw new IllegalStateException(
                    "gateway.auth.sa-token.jwt-secret-key must not be blank");
        }
    }
}
