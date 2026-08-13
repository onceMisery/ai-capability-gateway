package com.ai.gateway.adapter.auth.satoken;

import cn.dev33.satoken.jwt.SaJwtUtil;
import cn.dev33.satoken.SaManager;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.TokenIssuerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

/**
 * Sa-Token backed {@link AuthenticationPort} reference implementation.
 *
 * <p>Resolves the caller credential from the {@link RequestContext}
 * (Authorization header, cookie, or query parameter) and verifies it as a
 * Sa-Token JWT using {@link SaJwtUtil}. On successful verification the JWT
 * claims are mapped onto the internal {@link Principal} structure.</p>
 *
 * <p>This adapter is framework-agnostic at its core: it depends on
 * {@code sa-token-core}/{@code sa-token-jwt} only, not on the Sa-Token
 * Spring Boot starter, so it introduces no auto-configuration intrusion.
 * It is stateless — JWT signature verification does not require a session
 * store — which keeps it runnable before the Redis infrastructure
 * (tech-selection milestone M2) is available. Session persistence via
 * {@code sa-token-dao-redisson} can be layered on later.</p>
 *
 * <p>Recognized JWT claims:</p>
 * <ul>
 * <li>{@code loginId} (Sa-Token standard) or {@code sub} — the subject</li>
 * <li>{@code orgId} — the verified organization context</li>
 * <li>{@code roles} — role array</li>
 * <li>{@code permissions} — permission array</li>
 * </ul>
 *
 * @see SaTokenAuthProperties
 * @since 0.1.0
 */
public class SaTokenAuthenticationAdapter implements AuthenticationPort, TokenIssuerPort {

    private static final Logger log = LoggerFactory.getLogger(SaTokenAuthenticationAdapter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_METHOD = "SA_TOKEN_JWT";

    private static final String CLAIM_LOGIN_ID = "loginId";
    private static final String CLAIM_SUBJECT = "sub";
    private static final String CLAIM_ORG_ID = "orgId";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMISSIONS = "permissions";
    private static final String CLAIM_SESSION_ID = "sid";
    private static final String CLAIM_TOKEN_USE = "tokenUse";
    private static final String CLAIM_ISSUED_AT = "iat";
    private static final String TOKEN_USE_ACCESS = "access";
    private static final String TOKEN_USE_REFRESH = "refresh";
    private static final String SESSION_KEY_PREFIX = "gateway:auth:session:";

    private final SaTokenAuthProperties properties;

    /**
     * Constructs a new adapter.
     *
     * @param properties the Sa-Token authentication properties; never
     * {@code null}
     * @throws NullPointerException if {@code properties} is null
     */
    public SaTokenAuthenticationAdapter(SaTokenAuthProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        properties.validate();
    }

    @Override
    public Principal authenticate(RequestContext context) {
        Objects.requireNonNull(context, "context must not be null");
        String token = resolveToken(context);
        if (token == null || token.isBlank()) {
            throw new SecurityException(
                    "AUTHENTICATION_FAILED: no credential found in request context");
        }
        return validateToken(token);
    }

    @Override
    public Principal validateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new SecurityException("AUTHENTICATION_FAILED: token is null or blank");
        }
        JSONObject payloads;
        try {
            // Verify signature and loginType; timeout is enforced via the JWT
            // expiry claim when present, so the Sa-Token timeout field is not
            // strictly required here.
            payloads = SaJwtUtil.getPayloads(
                    token, properties.getLoginType(), properties.getJwtSecretKey());
        } catch (Exception e) {
            log.warn("Sa-Token JWT verification failed: {}", e.getMessage());
            throw new SecurityException("AUTHENTICATION_FAILED: invalid or expired token", e);
        }
        requireTokenUse(payloads, TOKEN_USE_ACCESS);
        requireActiveSession(payloads);
        return toPrincipal(payloads);
    }

    /**
     * Issues a Sa-Token JWT for the given subject and claims.
     *
     * <p>Primarily useful for tests and for deployments where the gateway's
     * own auth server mints tokens. The produced token carries the Sa-Token
     * {@code loginType} and {@code loginId} claims plus the supplied extra
     * data, and can be verified by {@link #validateToken(String)}.</p>
     *
     * @param subject the subject (login id)
     * @param extraData additional claims (orgId, roles, permissions, ...);
     * may be {@code null}
     * @return the signed JWT string
     */
    @Override
    public TokenPair issueTokenPair(String subject, Map<String, Object> extraData) {
        Objects.requireNonNull(subject, "subject must not be null");
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> baseClaims = new HashMap<>();
        if (extraData != null) {
            baseClaims.putAll(extraData);
        }
        baseClaims.put(CLAIM_SESSION_ID, sessionId);
        baseClaims.put(CLAIM_ISSUED_AT, Instant.now().getEpochSecond());

        Map<String, Object> accessClaims = new HashMap<>(baseClaims);
        accessClaims.put(CLAIM_TOKEN_USE, TOKEN_USE_ACCESS);
        String accessToken = SaJwtUtil.createToken(
                properties.getLoginType(), subject, "gateway-access",
                properties.getAccessTokenTimeoutSeconds(), accessClaims,
                properties.getJwtSecretKey());

        Map<String, Object> refreshClaims = new HashMap<>(baseClaims);
        refreshClaims.put(CLAIM_TOKEN_USE, TOKEN_USE_REFRESH);
        String refreshToken = SaJwtUtil.createToken(
                properties.getLoginType(), subject, "gateway-refresh",
                properties.getRefreshTokenTimeoutSeconds(), refreshClaims,
                properties.getJwtSecretKey());

        SaManager.getSaTokenDao().set(sessionKey(sessionId), "ACTIVE",
                properties.getRefreshTokenTimeoutSeconds());
        return new TokenPair(accessToken, refreshToken,
                properties.getAccessTokenTimeoutSeconds(),
                properties.getRefreshTokenTimeoutSeconds());
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        JSONObject payloads;
        try {
            payloads = SaJwtUtil.getPayloads(refreshToken,
                    properties.getLoginType(), properties.getJwtSecretKey());
            requireTokenUse(payloads, TOKEN_USE_REFRESH);
            requireActiveSession(payloads);
        } catch (Exception e) {
            throw new SecurityException("AUTHENTICATION_FAILED: invalid or expired refresh token", e);
        }
        String subject = firstNonBlank(
                payloads.getStr(CLAIM_LOGIN_ID), payloads.getStr(CLAIM_SUBJECT));
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_ORG_ID, payloads.getLong(CLAIM_ORG_ID, 0L));
        claims.put(CLAIM_ROLES, toStringList(payloads.get(CLAIM_ROLES)));
        claims.put(CLAIM_PERMISSIONS, toStringList(payloads.get(CLAIM_PERMISSIONS)));
        revokeSession(payloads);
        return issueTokenPair(subject, claims);
    }

    @Override
    public void revokeToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            JSONObject payloads = SaJwtUtil.getPayloadsNotCheck(
                    token, properties.getLoginType(), properties.getJwtSecretKey());
            revokeSession(payloads);
        } catch (Exception e) {
            throw new SecurityException("AUTHENTICATION_FAILED: invalid token", e);
        }
    }

    private void requireTokenUse(JSONObject payloads, String expected) {
        if (!expected.equals(payloads.getStr(CLAIM_TOKEN_USE))) {
            throw new SecurityException("AUTHENTICATION_FAILED: wrong token type");
        }
    }

    private void requireActiveSession(JSONObject payloads) {
        String sessionId = payloads.getStr(CLAIM_SESSION_ID);
        if (sessionId == null || !"ACTIVE".equals(
                SaManager.getSaTokenDao().get(sessionKey(sessionId)))) {
            throw new SecurityException("AUTHENTICATION_FAILED: session revoked or expired");
        }
    }

    private void revokeSession(JSONObject payloads) {
        String sessionId = payloads.getStr(CLAIM_SESSION_ID);
        if (sessionId != null) {
            SaManager.getSaTokenDao().delete(sessionKey(sessionId));
        }
    }

    private static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String resolveToken(RequestContext context) {
        // 1. Authorization header (Bearer scheme preferred)
        String authHeader = context.header("Authorization");
        if (authHeader != null && !authHeader.isBlank()) {
            return authHeader.startsWith(BEARER_PREFIX)
                    ? authHeader.substring(BEARER_PREFIX.length()).trim()
                    : authHeader.trim();
        }
        // 2. Named header (token name)
        String namedHeader = context.header(properties.getTokenName());
        if (namedHeader != null && !namedHeader.isBlank()) {
            return namedHeader.trim();
        }
        // 3. Cookie
        String cookie = context.cookies().get(properties.getTokenName());
        if (cookie != null && !cookie.isBlank()) {
            return cookie.trim();
        }
        // 4. Query parameter
        String query = context.queryParams().get(properties.getTokenName());
        if (query != null && !query.isBlank()) {
            return query.trim();
        }
        return null;
    }

    private Principal toPrincipal(JSONObject payloads) {
        String subject = firstNonBlank(
                payloads.getStr(CLAIM_LOGIN_ID), payloads.getStr(CLAIM_SUBJECT));
        if (subject == null || subject.isBlank()) {
            throw new SecurityException(
                    "AUTHENTICATION_FAILED: token carries no subject claim");
        }
        long orgId = payloads.getLong(CLAIM_ORG_ID, 0L);
        List<String> roles = toStringList(payloads.get(CLAIM_ROLES));
        List<String> permissions = toStringList(payloads.get(CLAIM_PERMISSIONS));
        long issuedAt = payloads.getLong(CLAIM_ISSUED_AT, Instant.now().getEpochSecond());
        return new Principal(subject, orgId, roles, permissions,
                Instant.ofEpochSecond(issuedAt), AUTH_METHOD);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }

    private static List<String> toStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
        } else if (value instanceof CharSequence cs && !cs.toString().isBlank()) {
            result.add(cs.toString());
        }
        return result;
    }
}
