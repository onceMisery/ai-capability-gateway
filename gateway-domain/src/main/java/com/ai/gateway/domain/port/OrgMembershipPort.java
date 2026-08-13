package com.ai.gateway.domain.port;

/**
 * Port for verifying organization membership.
 *
 * <p>Specifies that {@code orgId} is the organization context
 * selected by the user during the session, not an inherent claim of the
 * credential. Unless the token already contains an org claim signed by the
 * identity system, the gateway must verify the user's membership in that
 * organization before writing it into the {@link com.ai.gateway.domain.model.Principal}.</p>
 *
 * <p>An unverified {@code orgId} must never enter the Principal or be used
 * for PRINCIPAL parameter binding. The verification result
 * may be cached with a short TTL; cache miss combined with data source
 * unavailability must adopt Fail Closed — the request must
 * not be allowed through, and the gateway must not fall back to trusting
 * a client-self-reported {@code orgId}.</p>
 *
 * <p>This is a critical security increment over legacy entry points that
 * trust client-reported org headers with silent defaults. No implementation
 * may degrade to directly trusting client headers.</p>
 *
 * <p>Adapters implementing this port query the user service or
 * authorization data source. The port is a pure abstraction
 * with no framework dependencies.</p>
 *
 * @see com.ai.gateway.domain.model.Principal
 * @since 0.1.0
 */
public interface OrgMembershipPort {

    /**
     * Verifies that the given subject belongs to the specified organization.
     *
     * <p>: after the user selects an {@code orgId}, the gateway
     * verifies membership via the authorization data source. The result may
     * be cached with a short TTL. On cache miss with data source
     * unavailability, Fail Closed is adopted — {@code false} is returned.</p>
     *
     * @param subject the authenticated user identifier (e.g., "user-123")
     * @param orgId the organization context selected by the user
     * @return {@code true} if the subject is a member of the organization;
     * {@code false} otherwise or on data source failure (Fail Closed)
     */
    boolean verifyMembership(String subject, long orgId);
}
