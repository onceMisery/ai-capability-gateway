package com.ai.gateway.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * The authenticated caller identity, constructed server-side by the gateway
 * after verifying the enterprise authentication chain.
 *
 * <p>Defines two authentication modes:</p>
 * <ol>
 * <li>JWT/OIDC (target mode): the gateway verifies signature, issuer,
 * audience, expiry, and required claims.</li>
 * <li>Enterprise SSO server-side validation (transition mode): the
 * gateway validates credentials via the SSO system's introspection
 * endpoint. No SSO SDK JAR is introduced.</li>
 * </ol>
 *
 * <p>Both modes produce the same internal Principal structure. The
 * {@code orgId} is the organization context selected by the user during
 * the session, not an inherent claim of the credential. Unless the token
 * already contains an org claim signed by the identity system, the gateway
 * must verify the user's membership in that organization before writing
 * it into the Principal. An unverified {@code orgId} must
 * never enter the Principal or be used for PRINCIPAL parameter binding.</p>
 *
 * <p>Request body, query parameters, and custom headers carrying
 * {@code orgId}, {@code tenantId}, or {@code userId} must never override
 * the Principal.</p>
 *
 * @param subject the authenticated user identifier (e.g., "user-123")
 * @param orgId the verified organization context for this session
 * @param roles the user's roles within the organization
 * @param permissions the user's capability permissions
 * @param authTime the time at which authentication was completed
 * @param authMethod the authentication method used (e.g., "JWT", "SSO")
 * @since 0.1.0
 */
public record Principal(
        String subject,
        long orgId,
        List<String> roles,
        List<String> permissions,
        Instant authTime,
        String authMethod
) {
    /**
     * Compact constructor performing defensive copying of mutable collections.
     *
     * @param subject the authenticated user identifier
     * @param orgId the verified organization context
     * @param roles the user's roles
     * @param permissions the user's permissions
     * @param authTime the authentication timestamp
     * @param authMethod the authentication method
     */
    public Principal {
        java.util.Objects.requireNonNull(subject, "subject must not be null");
        java.util.Objects.requireNonNull(roles, "roles must not be null");
        java.util.Objects.requireNonNull(permissions, "permissions must not be null");
        java.util.Objects.requireNonNull(authTime, "authTime must not be null");
        java.util.Objects.requireNonNull(authMethod, "authMethod must not be null");
        roles = List.copyOf(roles);
        permissions = List.copyOf(permissions);
    }
}
