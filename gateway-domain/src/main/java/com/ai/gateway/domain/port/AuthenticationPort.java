package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;

/**
 * Port for authenticating callers and constructing the internal
 * {@link Principal} identity.
 *
 * <p>Defines two authentication entry points, both of which produce
 * the same internal Principal structure:</p>
 * <ol>
 * <li><b>{@link #authenticate(RequestContext)}</b>: resolves the caller
 * identity from the full request context (headers, cookies, query
 * parameters). The implementation is free to read a Bearer token from the
 * {@code Authorization} header, a session cookie, or a query parameter.</li>
 * <li><b>{@link #validateToken(String)}</b>: validates a raw token string,
 * used for cross-service call scenarios where the token has already been
 * extracted and forwarded.</li>
 * </ol>
 *
 * <p>Target mode is JWT/OIDC: the gateway verifies signature, issuer,
 * audience, expiry, and required claims. Transition mode is enterprise SSO
 * server-side validation via the SSO system's introspection endpoint; no
 * SSO SDK JAR is introduced, only network calls are permitted.</p>
 *
 * <p>Identity headers self-declared by the client do not constitute
 * authentication results. The {@code orgId} is the organization context
 * selected by the user during the session, not an inherent claim of the
 * credential. Unless the token already contains an org claim signed by
 * the identity system, the gateway must verify the user's membership in
 * that organization before writing it into the Principal.</p>
 *
 * <p>Request body, query parameters, and custom headers carrying
 * {@code orgId}, {@code tenantId}, or {@code userId} must never override
 * the Principal. Write operations may declare {@code maxAuthAgeSeconds},
 * {@code requiredAcr}, and {@code requiredAmr}; Confirm must re-check
 * authentication freshness and MFA level.</p>
 *
 * <p>Adapters implementing this port handle JWT verification or SSO
 * introspection (e.g., Sa-Token, Spring Security OAuth2, CAS, or a custom
 * SSO). The port is a pure abstraction with no framework dependencies.</p>
 *
 * @see Principal
 * @see RequestContext
 * @since 0.1.0
 */
public interface AuthenticationPort {

    /**
     * Authenticates a caller by resolving the identity from the request
     * context.
     *
     * <p>The implementation extracts the credential (Bearer token, session
     * cookie, or SSO ticket) from the context and verifies it. In JWT/OIDC
     * target mode the gateway verifies signature, issuer, audience, expiry,
     * and required claims. Authentication failures are written to a separate
     * security audit stream; if that stream is unavailable, the entry point
     * adopts Fail Closed.</p>
     *
     * @param context the request context carrying headers, cookies, and
     * query parameters; never {@code null}
     * @return the authenticated principal; never {@code null}
     * @throws {@link ErrorCode#AUTHENTICATION_FAILED}
     * if no valid credential is present or verification fails
     */
    Principal authenticate(RequestContext context);

    /**
     * Validates a raw token and resolves the caller identity.
     *
     * <p>Used for cross-service call scenarios where the token has already
     * been extracted from the inbound request and forwarded explicitly.
     * Behaves identically to {@link #authenticate(RequestContext)} once the
     * token is resolved.</p>
     *
     * @param token the bearer token (JWT or OIDC ID token); never
     * {@code null}
     * @return the authenticated principal; never {@code null}
     * @throws  {@link ErrorCode#AUTHENTICATION_FAILED}
     * if the token is invalid, expired, or fails verification
     */
    Principal validateToken(String token);
}
