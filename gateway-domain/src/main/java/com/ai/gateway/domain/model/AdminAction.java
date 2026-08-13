package com.ai.gateway.domain.model;

/**
 * The set of privileged control-plane (administrative) actions that require
 * an explicit authorization decision.
 *
 * <p>These actions mutate the capability catalog or its lifecycle and are
 * therefore gated by {@link
 * com.ai.gateway.domain.port.AuthorizationPort#authorizeAdmin(Principal,
 * AdminAction)}. The enumeration is closed: new administrative actions must
 * be added here deliberately so that authorization coverage stays
 * explicit.</p>
 *
 * @since 0.1.0
 */
public enum AdminAction {

    /** Reads control-plane state, monitoring data, ACLs, or configuration. */
    READ,

    /** Imports a capability manifest into the catalog (10-step validation). */
    IMPORT,

    /** Approves an imported manifest, advancing its lifecycle state. */
    APPROVE,

    /** Publishes a catalog snapshot to an environment. */
    PUBLISH,

    /** Rolls back an environment to a historical snapshot version. */
    ROLLBACK,

    /** Suspends a capability via emergency suspension. */
    SUSPEND,

    /** Manages ACL entries, roles, and permissions. */
    MANAGE_ACL,

    /** Modifies gateway configuration or rate-limit rules. */
    CONFIGURE
}
