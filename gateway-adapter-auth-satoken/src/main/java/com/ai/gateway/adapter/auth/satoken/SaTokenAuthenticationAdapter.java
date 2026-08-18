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
 * 基于 Sa-Token 的 {@link AuthenticationPort} 参考实现。
 *
 * <p>从 {@link RequestContext}（Authorization 请求头、Cookie 或查询参数）中解析
 * 调用方凭证，并使用 {@link SaJwtUtil} 将其作为 Sa-Token JWT 进行校验。校验通过后，
 * JWT 声明会被映射到内部的 {@link Principal} 结构中。</p>
 *
 * <p>该适配器核心是框架无关的：它只依赖 {@code sa-token-core}/{@code sa-token-jwt}，
 * 而不依赖 Sa-Token 的 Spring Boot 启动器，因此不会引入自动配置层面的侵入。它是
 * 无状态的——JWT 签名校验不需要会话存储——因此可以在 Redis 基础设施（技术选型
 * 里程碑 M2）可用之前正常运行。后续可通过 {@code sa-token-dao-redisson} 叠加
 * 会话持久化能力。</p>
 *
 * <p>可识别的 JWT 声明：</p>
 * <ul>
 * <li>{@code loginId}（Sa-Token 标准）或 {@code sub}——主体标识</li>
 * <li>{@code orgId}——已校验的组织上下文</li>
 * <li>{@code roles}——角色数组</li>
 * <li>{@code permissions}——权限数组</li>
 * </ul>
 *
 * @see SaTokenAuthProperties
 * @author cmiracle@163.com
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
     * 构造一个新的适配器。
     *
     * @param properties Sa-Token 认证属性；不能为 {@code null}
     * @throws NullPointerException 如果 {@code properties} 为 null
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
            // 校验签名和 loginType；超时由 JWT 中的过期声明（存在时）强制执行，
            // 因此这里并不严格要求 Sa-Token 的超时字段。
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
     * 为给定的主体和声明签发 Sa-Token JWT。
     *
     * <p>主要用于测试，以及网关自身认证服务器签发令牌的部署场景。生成的令牌携带
     * Sa-Token 的 {@code loginType} 和 {@code loginId} 声明，以及传入的附加数据，
     * 可通过 {@link #validateToken(String)} 进行校验。</p>
     *
     * @param subject 主体标识（登录 ID）
     * @param extraData 附加声明（orgId、roles、permissions 等）；可以为 {@code null}
     * @return 签名后的 JWT 字符串
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
        // 1. Authorization 请求头（优先使用 Bearer 方案）
        String authHeader = context.header("Authorization");
        if (authHeader != null && !authHeader.isBlank()) {
            return authHeader.startsWith(BEARER_PREFIX)
                    ? authHeader.substring(BEARER_PREFIX.length()).trim()
                    : authHeader.trim();
        }
        // 2. 命名请求头（令牌名称）
        String namedHeader = context.header(properties.getTokenName());
        if (namedHeader != null && !namedHeader.isBlank()) {
            return namedHeader.trim();
        }
        // 3. Cookie
        String cookie = context.cookies().get(properties.getTokenName());
        if (cookie != null && !cookie.isBlank()) {
            return cookie.trim();
        }
        // 4. 查询参数
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
