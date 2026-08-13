package com.ai.gateway.example.demo;

import cn.dev33.satoken.jwt.SaJwtUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mints Sa-Token-compatible JWTs that the gateway's
 * {@code SaTokenAuthenticationAdapter} will accept.
 *
 * <p>The produced token follows the Sa-Token JWT layout:</p>
 * <ul>
 * <li>{@code loginType} — the value configured in
 * {@code gateway.auth.sa-token.login-type} (default {@code login}).</li>
 * <li>{@code loginId} — the caller's subject identifier.</li>
 * <li>{@code eff} — absolute expiration epoch millis
 * ({@code -1} = never expire).</li>
 * <li>Custom claims (e.g., {@code orgId}, {@code roles}, {@code permissions})
 * are added via {@code extraData} and flow through to the gateway's
 * {@code Principal}.</li>
 * </ul>
 *
 * <p>The token is signed with HS256 using the same secret configured in the
 * gateway as {@code gateway.auth.sa-token.jwt-secret-key}. Mismatched secrets
 * produce {@code AUTHENTICATION_FAILED} responses.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * SaTokenIssuer issuer = new SaTokenIssuer("shared-secret");
 * String jwt = issuer.issue("user-123", Map.of(
 *     "orgId", 10001L,
 *     "roles", List.of("user", "analyst")));
 * }</pre>
 *
 * @since 0.1.0
 */
public final class SaTokenIssuer {

    /**
     * Default Sa-Token login type (matches gateway default).
     */
    public static final String DEFAULT_LOGIN_TYPE = "login";

    /**
     * Default access token timeout in seconds (2 hours).
     */
    public static final long DEFAULT_ACCESS_TOKEN_TIMEOUT_SECONDS = 7200L;

    private final String secretKey;
    private final String loginType;

    /**
     * Constructs an issuer with the default login type ({@code login}).
     *
     * @param secretKey the HMAC-SHA256 signing secret; must match the
     * gateway's {@code gateway.auth.sa-token.jwt-secret-key}
     */
    public SaTokenIssuer(String secretKey) {
        this(secretKey, DEFAULT_LOGIN_TYPE);
    }

    /**
     * Constructs an issuer with an explicit login type.
     *
     * @param secretKey the HMAC-SHA256 signing secret
     * @param loginType the Sa-Token login type (e.g., {@code login},
     * {@code user}, {@code admin})
     */
    public SaTokenIssuer(String secretKey, String loginType) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException(
                    "secretKey must not be null or blank — use the same value "
                            + "as gateway.auth.sa-token.jwt-secret-key");
        }
        if (loginType == null || loginType.isBlank()) {
            throw new IllegalArgumentException("loginType must not be null or blank");
        }
        this.secretKey = secretKey;
        this.loginType = loginType;
    }

    /**
     * Issues an access token for the given subject with no extra claims and
     * the default 2-hour timeout.
     *
     * @param subject the caller subject (mapped to Sa-Token's {@code loginId})
     * @return the signed JWT
     */
    public String issue(String subject) {
        return issue(subject, Map.of(), DEFAULT_ACCESS_TOKEN_TIMEOUT_SECONDS);
    }

    /**
     * Issues an access token for the given subject with extra claims.
     *
     * @param subject the caller subject
     * @param extraData additional claims (e.g., orgId, roles, permissions)
     * @param timeoutSeconds token lifetime in seconds
     * @return the signed JWT
     */
    public String issue(String subject, Map<String, Object> extraData, long timeoutSeconds) {
        Objects.requireNonNull(subject, "subject must not be null");
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }

        Map<String, Object> claims = new HashMap<>();
        if (extraData != null) {
            claims.putAll(extraData);
        }
        return SaJwtUtil.createToken(loginType, subject, null, timeoutSeconds, claims, secretKey);
    }

    /**
     * Issues an access token for the given subject with role/permission
     * claims.
     *
     * @param subject the caller subject
     * @param orgId the organization context
     * @param roles the roles granted to the caller
     * @param permissions the permissions granted to the caller
     * @param timeoutSeconds token lifetime in seconds
     * @return the signed JWT
     */
    public String issue(String subject, long orgId, List<String> roles,
                        List<String> permissions, long timeoutSeconds) {
        Map<String, Object> extras = new HashMap<>();
        extras.put("orgId", orgId);
        extras.put("roles", roles != null ? roles : List.of());
        extras.put("permissions", permissions != null ? permissions : List.of());
        return issue(subject, extras, timeoutSeconds);
    }
}
