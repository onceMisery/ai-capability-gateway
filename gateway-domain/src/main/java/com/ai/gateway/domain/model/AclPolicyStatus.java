package com.ai.gateway.domain.model;

import java.util.Objects;

/** Runtime status of the capability ACL policy cache. */
public record AclPolicyStatus(
        boolean loadHealthy,
        int loadedEntryCount,
        String emptyAclDecision
) {

    public AclPolicyStatus {
        if (loadedEntryCount < 0) {
            throw new IllegalArgumentException("loadedEntryCount must not be negative");
        }
        emptyAclDecision = Objects.requireNonNull(
                emptyAclDecision, "emptyAclDecision must not be null");
        if (!"ALLOW".equals(emptyAclDecision) && !"DENY".equals(emptyAclDecision)) {
            throw new IllegalArgumentException("emptyAclDecision must be ALLOW or DENY");
        }
    }
}
