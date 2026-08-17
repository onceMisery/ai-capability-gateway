package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.AclPolicyStatus;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.SnapshotNotifier;
import com.ai.gateway.domain.port.TokenIssuerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default (stub) authentication/authorization wiring for the initial release.
 *
 * <p>Active when {@code gateway.auth.provider} is unset or {@code stub}.
 * Selecting a concrete provider (e.g., {@code gateway.auth.provider=sa-token})
 * deactivates these stubs and activates the corresponding adapter
 * configuration instead — this is the pluggability seam described in
 * {@code docs/extensibility-tech-selection.md}.</p>
 *
 * <p>The stubs are development-only: authentication accepts any non-blank
 * Bearer token and authorization allows all authenticated calls. Production
 * must use the configured Sa-Token and ACL adapters.</p>
 *
 * @since 0.1.0
 */
@Configuration
@ConditionalOnProperty(name = "gateway.auth.provider", havingValue = "stub", matchIfMissing = true)
public class StubAuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StubAuthConfiguration.class);

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
     * Stub {@link AuthenticationPort} for the initial release.
     *
     * <p>Resolves a subject from the Authorization header (or the
     * {@code subject} query parameter) and builds a minimal {@link Principal}.
     * Production must replace this with a real JWT/OIDC or SSO adapter
     * (e.g., the Sa-Token adapter).</p>
     *
     * @return the stub authentication port
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
     * Development-only stub {@link AuthorizationPort}.
     *
     * <p>"In the initial release, authorization is optional. All
     * authenticated users may call all published read-only capabilities.
     * Visibility authorization degrades to authentication status check;
     * execution authorization degrades to Schema validation and Principal
     * parameter injection."</p>
     *
     * @return the stub authorization port
     */
    @Bean
    public AuthorizationPort authorizationPort() {
        return new AuthorizationPort() {
            @Override
            public List<CapabilityManifest> filterVisibleCapabilities(
                    Principal principal, List<CapabilityManifest> candidates) {
                // Initial release: all authenticated users see all capabilities
                return candidates;
            }

            @Override
            public boolean authorizeExecution(
                    Principal principal, String capabilityId, String version) {
                // Initial release: allow all authenticated users
                return true;
            }

            @Override
            public boolean authorizeAdmin(Principal principal, AdminAction action) {
                // Initial release: allow all authenticated users
                return true;
            }

            @Override
            public AclPolicyStatus aclPolicyStatus() {
                return new AclPolicyStatus(true, 0, "ALLOW");
            }
        };
    }

    /**
     * Stub {@link SnapshotNotifier} that only logs notifications.
     *
     * <p>Active when {@code gateway.cache.provider} is unset or {@code stub}.
     * Selecting {@code gateway.cache.provider=redis} activates the Redis
     * pub/sub notifier in {@code RedisCacheConfiguration} instead.</p>
     *
     * @return the stub snapshot notifier
     */
    @Bean
    @ConditionalOnProperty(name = "gateway.cache.provider", havingValue = "stub", matchIfMissing = true)
    public SnapshotNotifier snapshotNotifier() {
        return new SnapshotNotifier() {
            @Override
            public void notifySnapshotPublished(long snapshotVersion) {
                log.info("Snapshot published notification (stub): version={}", snapshotVersion);
            }

            @Override
            public void notifySnapshotSuspended(long snapshotVersion) {
                log.warn("Snapshot suspended notification (stub): version={}", snapshotVersion);
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
