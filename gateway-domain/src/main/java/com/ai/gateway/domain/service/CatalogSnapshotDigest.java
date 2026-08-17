package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Canonical digest for a catalog snapshot.
 *
 * <p>The digest binds the target environment, policy reference, snapshot
 * version and every complete Manifest digest in deterministic ID/version
 * order. Callers must use this service for both publication and activation so
 * a digest cannot silently degrade to an ID/version-only checksum.</p>
 */
public final class CatalogSnapshotDigest {

    private CatalogSnapshotDigest() {
    }

    public static String sha256(CatalogSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return sha256(snapshot.snapshotVersion(), snapshot.environment(),
                snapshot.policyRef(), snapshot.capabilities());
    }

    public static String sha256(long snapshotVersion, String environment,
                                String policyRef, List<CapabilityManifest> capabilities) {
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        StringBuilder canonical = new StringBuilder(256);
        append(canonical, "snapshotVersion", Long.toString(snapshotVersion));
        append(canonical, "environment", environment);
        append(canonical, "policyRef", policyRef == null ? "" : policyRef);
        capabilities.stream()
                .sorted(Comparator.comparing((CapabilityManifest m) -> m.metadata().id())
                        .thenComparing(m -> m.metadata().version()))
                .forEach(manifest -> {
                    append(canonical, "capabilityId", manifest.metadata().id());
                    append(canonical, "capabilityVersion", manifest.metadata().version());
                    append(canonical, "manifestDigest", ManifestDigest.sha256(manifest));
                });
        return Sha256Digest.sha256Hex(canonical.toString());
    }

    private static void append(StringBuilder out, String key, String value) {
        out.append(key.length()).append(':').append(key)
                .append(value.length()).append(':').append(value).append(';');
    }
}
