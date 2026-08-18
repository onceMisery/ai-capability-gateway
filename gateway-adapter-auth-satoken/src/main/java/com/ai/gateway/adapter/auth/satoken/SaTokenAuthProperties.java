package com.ai.gateway.adapter.auth.satoken;

import lombok.Data;

import java.util.Objects;

/**
 * Sa-Token 认证适配器的配置属性。
 *
 * <p>从 {@code gateway.auth.sa-token.*} 命名空间绑定。这些值驱动 JWT 校验
 * （密钥、登录类型）和令牌解析（令牌名称在请求头 / Cookie / 查询参数来源中的
 * 定位方式）。</p>
 *
 * <p>这是一个普通的属性持有类——特意避免使用 Spring Boot 的
 * {@code @ConfigurationProperties}，使适配器模块不依赖 Spring Boot 的自动配置
 * 机制，与仅引入 {@code sa-token-core} 的技术选型决策保持一致。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Data
public class SaTokenAuthProperties {

    /**
     * 默认令牌名称，用于在请求头、Cookie 和查询参数中定位凭证。
     */
    public static final String DEFAULT_TOKEN_NAME = "Authorization";

    /**
     * 签发的 JWT 中携带的默认 Sa-Token 登录类型。
     */
    public static final String DEFAULT_LOGIN_TYPE = "login";

    /**
     * 默认访问令牌超时时间（秒），即 2 小时。
     */
    public static final long DEFAULT_ACCESS_TOKEN_TIMEOUT_SECONDS = 7200L;

    /**
     * 默认刷新令牌超时时间（秒），即 7 天。
     */
    public static final long DEFAULT_REFRESH_TOKEN_TIMEOUT_SECONDS = 604800L;

    private String tokenName = DEFAULT_TOKEN_NAME;
    private String loginType = DEFAULT_LOGIN_TYPE;
    private String jwtSecretKey;
    private long accessTokenTimeoutSeconds = DEFAULT_ACCESS_TOKEN_TIMEOUT_SECONDS;
    private long refreshTokenTimeoutSeconds = DEFAULT_REFRESH_TOKEN_TIMEOUT_SECONDS;

    /**
     * 校验最小必需配置是否齐全。
     *
     * @throws IllegalStateException 如果未配置 JWT 密钥
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
