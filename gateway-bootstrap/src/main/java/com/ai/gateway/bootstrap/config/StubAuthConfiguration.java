package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.AclPolicyStatus;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.TokenIssuerPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 初始发布版本的默认（桩）认证/授权装配。
 *
 * <p>当 {@code gateway.auth.provider} 未设置或为 {@code stub} 时生效。
 * 选择具体提供者（如 {@code gateway.auth.provider=sa-token}）会停用这些桩，
 * 转而激活对应的适配器配置——这正是 {@code docs/extensibility-tech-selection.md}
 * 中描述的可插拔接缝。</p>
 *
 * <p>这些桩仅用于开发：认证接受任意非空 Bearer 令牌，授权放行所有已认证调用。
 * 生产环境必须使用配置好的 Sa-Token 与 ACL 适配器。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
@ConditionalOnProperty(name = "gateway.auth.provider", havingValue = "stub", matchIfMissing = true)
public class StubAuthConfiguration {

    private static final String BEARER_PREFIX = "Bearer ";
    private final StubTokenService tokenService = new StubTokenService();

    public StubAuthConfiguration(GatewayProperties properties) {
        assertStubAllowed(properties.getEnvironment());
    }

    static void assertStubAllowed(String environment) {
        if ("production".equalsIgnoreCase(environment)) {
            throw new IllegalStateException(
                    "gateway.auth.provider=stub is forbidden in production");
        }
    }

    /**
     * 初始发布版本的桩 {@link AuthenticationPort}。
     *
     * <p>从 Authorization 头（或 {@code subject} 查询参数）解析主体，
     * 并构建一个最小化的 {@link Principal}。生产环境必须用真实的
     * JWT/OIDC 或 SSO 适配器（如 Sa-Token 适配器）替换它。</p>
     *
     * @return 桩认证端口
     */
    @Bean
    public AuthenticationPort authenticationPort() {
        return tokenService;
    }

    @Bean
    public TokenIssuerPort tokenIssuerPort() {
        return tokenService;
    }

    /**
     * 仅用于开发的桩 {@link AuthorizationPort}。
     *
     * <p>“初始发布版本中，授权为可选项。所有已认证用户均可调用所有已发布的
     * 只读能力。可见性授权降级为仅检查认证状态；执行授权降级为 Schema
     * 校验与 Principal 参数注入。”</p>
     *
     * @return 桩授权端口
     */
    @Bean
    public AuthorizationPort authorizationPort() {
        return new AuthorizationPort() {
            @Override
            public CapabilityVisibility resolveVisibility(Principal principal) {
                return CapabilityVisibility.all(1L);
            }

            @Override
            public long currentPolicyEpoch() {
                return 1L;
            }

            @Override
            public List<CapabilityManifest> filterVisibleCapabilities(
                    Principal principal, List<CapabilityManifest> candidates) {
                // 初始发布：所有已认证用户可见全部能力
                return candidates;
            }

            @Override
            public boolean authorizeExecution(
                    Principal principal, String capabilityId, String version) {
                // 初始发布：放行所有已认证用户
                return true;
            }

            @Override
            public boolean authorizeAdmin(Principal principal, AdminAction action) {
                // 初始发布：放行所有已认证用户
                return true;
            }

            @Override
            public AclPolicyStatus aclPolicyStatus() {
                return new AclPolicyStatus(true, 0, "ALLOW");
            }
        };
    }

    private static final class StubTokenService
            implements AuthenticationPort, TokenIssuerPort {
        private static final long ACCESS_TTL = 7200L;
        private static final long REFRESH_TTL = 604800L;
        private final Map<String, StubSession> tokens = new ConcurrentHashMap<>();

        @Override
        public Principal authenticate(RequestContext context) {
            String token = resolveToken(context);
            if (token == null || token.isBlank()) {
                throw new SecurityException(
                        "AUTHENTICATION_FAILED: no credential found in request context");
            }
            return validateToken(token);
        }

        @Override
        public Principal validateToken(String token) {
            if (!token.startsWith("console-stub-")) {
                return new Principal(token, 0L, List.of("user"), List.of("*"),
                        Instant.now(), "STUB-JWT");
            }
            StubSession session = tokens.get(token);
            if (session == null || !"access".equals(session.tokenUse())
                    || session.expiresAt().isBefore(Instant.now())) {
                throw new SecurityException("AUTHENTICATION_FAILED: invalid or expired stub token");
            }
            return session.principal();
        }

        @Override
        public TokenPair issueTokenPair(String subject, Map<String, Object> extraData) {
            String sessionId = UUID.randomUUID().toString();
            String access = "console-stub-access-" + UUID.randomUUID();
            String refresh = "console-stub-refresh-" + UUID.randomUUID();
            Principal principal = new Principal(subject, 0L, List.of("admin"), List.of("*"),
                    Instant.now(), "STUB-JWT");
            tokens.put(access, new StubSession(sessionId, "access", principal,
                    Instant.now().plusSeconds(ACCESS_TTL)));
            tokens.put(refresh, new StubSession(sessionId, "refresh", principal,
                    Instant.now().plusSeconds(REFRESH_TTL)));
            return new TokenPair(access, refresh, ACCESS_TTL, REFRESH_TTL);
        }

        @Override
        public TokenPair refresh(String refreshToken) {
            StubSession session = tokens.get(refreshToken);
            if (session == null || !"refresh".equals(session.tokenUse())
                    || session.expiresAt().isBefore(Instant.now())) {
                throw new SecurityException("AUTHENTICATION_FAILED: invalid refresh token");
            }
            revokeSession(session.sessionId());
            return issueTokenPair(session.principal().subject(), Map.of());
        }

        @Override
        public void revokeToken(String token) {
            StubSession session = tokens.get(token);
            if (session != null) {
                revokeSession(session.sessionId());
            }
        }

        private void revokeSession(String sessionId) {
            tokens.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
        }

        private String resolveToken(RequestContext context) {
            String authHeader = context.header("Authorization");
            if (authHeader != null && !authHeader.isBlank()) {
                return authHeader.startsWith(BEARER_PREFIX)
                        ? authHeader.substring(BEARER_PREFIX.length()).trim()
                        : authHeader.trim();
            }
            return context.queryParams().get("subject");
        }

        private record StubSession(String sessionId, String tokenUse,
                                   Principal principal, Instant expiresAt) {
        }
    }
}
