package com.ai.gateway.domain.model;

import java.util.Objects;

/** Principal-scoped authorization visibility pinned to one policy epoch. */
public record PolicySnapshot(
        long policyEpoch,
        boolean healthy,
        CapabilityVisibility visibility
) {

    public PolicySnapshot {
        if (policyEpoch < 0) {
            throw new IllegalArgumentException("policyEpoch must not be negative");
        }
        visibility = Objects.requireNonNull(visibility, "visibility must not be null");
        if (visibility.policyEpoch() != policyEpoch) {
            throw new IllegalArgumentException("visibility epoch must match policy snapshot");
        }
        if (visibility.healthy() != healthy) {
            throw new IllegalArgumentException("visibility health must match policy snapshot");
        }
    }

    public static PolicySnapshot from(CapabilityVisibility visibility) {
        Objects.requireNonNull(visibility, "visibility must not be null");
        return new PolicySnapshot(
                visibility.policyEpoch(), visibility.healthy(), visibility);
    }

    public static PolicySnapshot unavailable(long policyEpoch) {
        return from(CapabilityVisibility.unavailable(policyEpoch));
    }
}
