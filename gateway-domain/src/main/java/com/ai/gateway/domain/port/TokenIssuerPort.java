package com.ai.gateway.domain.port;

import java.util.Map;

/**
 * 为管理控制台签发认证令牌（如 JWT）的端口。
 *
 * <p>让控制台的认证流程能够签发令牌，而无需依赖特定的令牌实现（Sa-Token 等）。该端口
 * 是纯粹的领域抽象，不依赖任何框架。</p>
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
     * 为给定主体与声明签发一个带签名的令牌。
     *
     * @param subject  嵌入令牌中的主体（登录 ID）
     * @param extraData 附加声明（orgId、roles、permissions 等）；可为 {@code null}
     * @return 已签名的令牌字符串
     */
    default String issueToken(String subject, Map<String, Object> extraData) {
        return issueTokenPair(subject, extraData).accessToken();
    }

    TokenPair issueTokenPair(String subject, Map<String, Object> extraData);

    TokenPair refresh(String refreshToken);

    void revokeToken(String token);
}
