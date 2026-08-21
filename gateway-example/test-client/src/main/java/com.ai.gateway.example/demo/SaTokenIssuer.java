package com.ai.gateway.example.demo;

import cn.dev33.satoken.jwt.SaJwtUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 签发网关的 {@code SaTokenAuthenticationAdapter} 可接受的 Sa-Token 兼容 JWT。
 *
 * <p>生成的 Token 遵循 Sa-Token 的 JWT 布局：</p>
 * <ul>
 * <li>{@code loginType} — 网关 {@code gateway.auth.sa-token.login-type} 中配置的值
 * （默认 {@code login}）。</li>
 * <li>{@code loginId} — 调用方的主体标识。</li>
 * <li>{@code eff} — 绝对过期时间（epoch 毫秒，{@code -1} 表示永不过期）。</li>
 * <li>自定义声明（如 {@code orgId}、{@code roles}、{@code permissions}）通过
 * {@code extraData} 附加，并流入网关的 {@code Principal}。</li>
 * </ul>
 *
 * <p>Token 使用与网关 {@code gateway.auth.sa-token.jwt-secret-key} 相同的密钥以 HS256
 * 签名。密钥不匹配会返回 {@code AUTHENTICATION_FAILED}。</p>
 *
 * <p>使用示例：
 * <pre>{@code
 * SaTokenIssuer issuer = new SaTokenIssuer("shared-secret");
 * String jwt = issuer.issue("user-123", Map.of(
 *     "orgId", 10001L,
 *     "roles", List.of("user", "analyst")));
 * }</pre>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class SaTokenIssuer {

    /**
     * 默认 Sa-Token 登录类型（与网关默认值一致）。
     */
    public static final String DEFAULT_LOGIN_TYPE = "login";

    /**
     * 默认访问 Token 有效期（秒，即 2 小时）。
     */
    public static final long DEFAULT_ACCESS_TOKEN_TIMEOUT_SECONDS = 7200L;

    private final String secretKey;
    private final String loginType;

    /**
     * 以默认登录类型（{@code login}）构造签发器。
     *
     * @param secretKey HMAC-SHA256 签名密钥，必须与网关的
     * {@code gateway.auth.sa-token.jwt-secret-key} 一致
     */
    public SaTokenIssuer(String secretKey) {
        this(secretKey, DEFAULT_LOGIN_TYPE);
    }

    /**
     * 以显式登录类型构造签发器。
     *
     * @param secretKey HMAC-SHA256 签名密钥
     * @param loginType Sa-Token 登录类型（如 {@code login}、{@code user}、{@code admin}）
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
     * 为给定主体签发访问 Token，无附加声明，使用默认 2 小时有效期。
     *
     * @param subject 调用方主体（映射为 Sa-Token 的 {@code loginId}）
     * @return 已签名的 JWT
     */
    public String issue(String subject) {
        return issue(subject, Map.of(), DEFAULT_ACCESS_TOKEN_TIMEOUT_SECONDS);
    }

    /**
     * 为给定主体签发带附加声明的访问 Token。
     *
     * @param subject 调用方主体
     * @param extraData 附加声明（如 orgId、roles、permissions）
     * @param timeoutSeconds Token 有效期（秒）
     * @return 已签名的 JWT
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
     * 为给定主体签发带角色/权限声明的访问 Token。
     *
     * @param subject 调用方主体
     * @param orgId 组织上下文
     * @param roles 授予调用方的角色
     * @param permissions 授予调用方的权限
     * @param timeoutSeconds Token 有效期（秒）
     * @return 已签名的 JWT
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
