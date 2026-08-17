package com.ai.gateway.application.console;

import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.TokenIssuerPort;
import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.service.Sha256Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 管理后台认证用例
 *
 * <p>支持两种认证模式：
 * <ul>
 * <li><b>stub</b>: 开发模式，接受任意用户名/密码，返回占位令牌</li>
 * <li><b>sa-token</b>: 由 Controller 层处理凭证校验与令牌签发</li>
 * </ul>
 * </p>
 *
 * @since 0.1.0
 */
public final class ConsoleAuthUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAuthUseCase.class);

    private final TokenIssuerPort tokenIssuerPort;
    private final AuditPort auditPort;
    private final String authMode;
    private final String adminUsername;
    private final String adminPassword;

    /**
     * 构造 ConsoleAuthUseCase
     *
     * @param tokenIssuerPort 令牌签发端口
     * @param auditPort          审计端口
     * @param authMode           认证模式（"stub" 或 "sa-token"）
     */
    public ConsoleAuthUseCase(TokenIssuerPort tokenIssuerPort,
                               AuditPort auditPort,
                               String authMode) {
        this(tokenIssuerPort, auditPort, authMode, "", "");
    }

    public ConsoleAuthUseCase(TokenIssuerPort tokenIssuerPort,
                               AuditPort auditPort,
                               String authMode,
                               String adminUsername,
                               String adminPassword) {
        this.tokenIssuerPort = Objects.requireNonNull(tokenIssuerPort);
        this.auditPort = Objects.requireNonNull(auditPort);
        this.authMode = Objects.requireNonNull(authMode);
        this.adminUsername = adminUsername == null ? "" : adminUsername;
        this.adminPassword = adminPassword == null ? "" : adminPassword;
    }

    /**
     * 返回认证模式探测信息
     *
     * @return 包含 authProvider、consoleEnabled、loginMode 的映射
     */
    public Map<String, Object> getCapabilities() {
        boolean isStub = "stub".equals(authMode);
        return Map.of(
                "authProvider", authMode,
                "consoleEnabled", true,
                "loginMode", isStub ? "token" : "credentials"
        );
    }

    /**
     * 签发受会话管理的访问令牌和刷新令牌。
     *
     * @param username 用户名
     * @return 包含 token、expiresInSeconds、principal 的映射
     */
    public Map<String, Object> login(String username) {
        return login(username, null);
    }

    /** Validates console credentials at the application boundary before issuing tokens. */
    public Map<String, Object> login(String username, String password) {
        Objects.requireNonNull(username, "username must not be null");
        if (!"stub".equals(authMode)
                && (!constantTimeEquals(adminUsername, username)
                || !constantTimeEquals(adminPassword, password))) {
            recordLoginFailure(username);
            throw new SecurityException("invalid credentials");
        }
        Map<String, Object> claims = Map.of(
                "orgId", 0L,
                "roles", List.of("admin"),
                "permissions", List.of("*"));
        TokenIssuerPort.TokenPair tokenPair = tokenIssuerPort.issueTokenPair(username, claims);
        String authMethod = "stub".equals(authMode) ? "STUB_JWT" : "SA_TOKEN_JWT";

        Map<String, Object> principal = Map.of(
                "subject", username,
                "orgId", 0L,
                "roles", List.of("admin"),
                "permissions", List.of("*"),
                "authMethod", authMethod
        );

        try {
            recordLoginAudit(username, 0L, "SUCCESS");
        } catch (RuntimeException e) {
            tokenIssuerPort.revokeToken(tokenPair.accessToken());
            throw e;
        }

        log.info("Console login successful for user: {}", username);
        return Map.of(
                "status", "OK",
                "data", Map.of(
                        "token", tokenPair.accessToken(),
                        "refreshToken", tokenPair.refreshToken(),
                        "expiresInSeconds", tokenPair.expiresInSeconds(),
                        "refreshExpiresInSeconds", tokenPair.refreshExpiresInSeconds(),
                        "principal", principal
                )
        );
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Rotates a valid refresh token and returns a new token pair. */
    public Map<String, Object> refresh(String refreshToken) {
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
        TokenIssuerPort.TokenPair tokenPair = tokenIssuerPort.refresh(refreshToken);
        return Map.of(
                "status", "OK",
                "data", Map.of(
                        "token", tokenPair.accessToken(),
                        "refreshToken", tokenPair.refreshToken(),
                        "expiresInSeconds", tokenPair.expiresInSeconds(),
                        "refreshExpiresInSeconds", tokenPair.refreshExpiresInSeconds()));
    }

    /** Records a rejected credential attempt without storing the raw username. */
    public void recordLoginFailure(String username) {
        Objects.requireNonNull(username, "username must not be null");
        recordLoginAudit(username, 0L, "AUTHENTICATION_FAILED");
    }

    /**
     * 返回当前用户的 Profile 信息
     *
     * @param principal 已认证的 Principal
     * @return 包含 userId、username、roles、permissions 的映射
     */
    public Map<String, Object> me(Principal principal) {
        Objects.requireNonNull(principal, "principal must not be null");

        List<String> roles = principal.roles() != null ? principal.roles() : List.of();
        List<String> permissions = principal.permissions() != null ? principal.permissions() : List.of();

        return Map.of(
                "status", "OK",
                "data", Map.of(
                        "userId", principal.subject(),
                        "username", principal.subject(),
                        "orgId", principal.orgId(),
                        "roles", roles,
                        "permissions", permissions
                )
        );
    }

    /**
     * 记录登出审计事件
     *
     * @param principal 已认证的 Principal
     */
    public void logout(Principal principal, String accessToken) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        tokenIssuerPort.revokeToken(accessToken);
        log.info("Console logout for user: {}", principal.subject());

        auditPort.recordEvent(new AuditEvent(
                UUID.randomUUID().toString(),
                "CONSOLE_LOGOUT",
                Instant.now(),
                digestSubject(principal.subject()),
                principal.orgId(),
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                0L,
                null,
                null,
                "SUCCESS",
                0L,
                "{\"action\":\"console_logout\"}"
        ));
    }

    /**
     * 记录登录审计事件
     */
    private void recordLoginAudit(String subject, long orgId, String resultCode) {
        auditPort.recordEvent(new AuditEvent(
                UUID.randomUUID().toString(),
                "CONSOLE_LOGIN",
                Instant.now(),
                digestSubject(subject),
                orgId,
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                0L,
                null,
                null,
                resultCode,
                0L,
                "{\"action\":\"console_login\"}"
        ));
    }

    private String digestSubject(String subject) {
        return Sha256Digest.sha256Hex(subject);
    }
}
