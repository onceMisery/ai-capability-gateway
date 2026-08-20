package com.ai.gateway.domain.model;

import java.util.Set;

/** Principal-scoped visibility result bound to a monotonic policy epoch. */
public record CapabilityVisibility(
        long policyEpoch,
        boolean healthy,
        boolean allVisible,
        Set<CapabilityReference> visibleCapabilities
) {

    public CapabilityVisibility {
        if (policyEpoch < 0) {
            throw new IllegalArgumentException("policyEpoch must not be negative");
        }
        visibleCapabilities = visibleCapabilities == null
                ? Set.of() : Set.copyOf(visibleCapabilities);
        if (!healthy && (allVisible || !visibleCapabilities.isEmpty())) {
            throw new IllegalArgumentException("unhealthy visibility must fail closed");
        }
    }

    public static CapabilityVisibility all(long policyEpoch) {
        return new CapabilityVisibility(policyEpoch, true, true, Set.of());
    }

    public static CapabilityVisibility restricted(
            long policyEpoch, Set<CapabilityReference> capabilities) {
        return new CapabilityVisibility(policyEpoch, true, false, capabilities);
    }

    public static CapabilityVisibility unavailable(long policyEpoch) {
        return new CapabilityVisibility(policyEpoch, false, false, Set.of());
    }
}
