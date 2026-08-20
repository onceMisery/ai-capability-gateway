package com.ai.gateway.domain.model;

import java.util.Objects;

/** Stable capability identity used inside authorization decisions. */
public record CapabilityReference(String capabilityId, String version) {

    public CapabilityReference {
        capabilityId = requireText(capabilityId, "capabilityId");
        version = requireText(version, "version");
    }

    public static CapabilityReference from(CapabilityManifest manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        return new CapabilityReference(manifest.metadata().id(), manifest.metadata().version());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
