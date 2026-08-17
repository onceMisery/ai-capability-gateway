package com.ai.gateway.application.catalog;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory catalog manager that maintains the current active snapshot using
 * atomic reference swapping.
 *
 * <p>When a snapshot notification is received, the manager:</p>
 * <ol>
 * <li>Loads the new snapshot from PostgreSQL via {@link CatalogPort}.</li>
 * <li>Builds a retrieval index for fast capability lookup.</li>
 * <li>Verifies the snapshot digest for integrity.</li>
 * <li>Atomically replaces the in-memory reference.</li>
 * </ol>
 *
 * <p>On loading failure, the old snapshot is retained. The
 * instance exits the ready state after exceeding the maximum lag time.</p>
 *
 * <p>Each request is pinned to the snapshot version active at the start of
 * processing. The {@link #isStale(long)} method allows health
 * checks to determine if the instance's snapshot is behind the threshold.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: the snapshot reference is stored in an
 * {@link AtomicReference} and all reads are lock-free.</p>
 *
 * @see CatalogPort
 * @see CatalogSnapshot
 * @since 0.1.0
 */
public final class InMemoryCatalogManager {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCatalogManager.class);

    private final CatalogPort catalogPort;
    private final AtomicReference<CatalogSnapshot> currentSnapshot = new AtomicReference<>();
    private final AtomicReference<Long> lastLoadTime = new AtomicReference<>(0L);

    /**
     * A lookup index mapping "id:version" to the capability manifest, built
     * from the current snapshot. Rebuilt atomically with the snapshot.
     */
    private volatile Map<String, CapabilityManifest> capabilityIndex = Map.of();

    /**
     * Constructs a new InMemoryCatalogManager with the required dependency.
     *
     * @param catalogPort the port for loading snapshots from PostgreSQL
     * @throws NullPointerException if {@code catalogPort} is null
     */
    public InMemoryCatalogManager(CatalogPort catalogPort) {
        this.catalogPort = Objects.requireNonNull(catalogPort,
                "catalogPort must not be null");
    }

    /**
     * Loads the current snapshot from PostgreSQL, builds the retrieval index,
     * verifies the digest, and atomically replaces the in-memory reference
     *
     * <p>If loading or digest verification fails, the old snapshot is retained
     * and an error is logged. The instance should exit the ready state after
     * exceeding the maximum lag time.</p>
     *
     * @param environment the target environment (e.g., "production")
     * @return {@code true} if the snapshot was successfully loaded and activated
     */
    public boolean loadAndActivate(String environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        log.info("Loading catalog snapshot for environment: {}", environment);

        CatalogSnapshot newSnapshot;
        try {
            newSnapshot = catalogPort.loadCurrentSnapshot(environment);
        } catch (Exception e) {
            log.error("Failed to load snapshot from PostgreSQL: {}", e.getMessage());
            return false;
        }

        if (newSnapshot == null) {
            log.warn("No snapshot returned for environment: {}", environment);
            return false;
        }
        if (!environment.equals(newSnapshot.environment()) || newSnapshot.snapshotVersion() <= 0
                || newSnapshot.capabilities().isEmpty()) {
            log.warn("Snapshot is not ready for activation: expectedEnvironment={}, actualEnvironment={}, version={}, capabilities={}",
                    environment, newSnapshot.environment(), newSnapshot.snapshotVersion(),
                    newSnapshot.capabilities().size());
            return false;
        }

        // Verify the snapshot digest for integrity. Missing digests fail closed.
        String storedDigest = newSnapshot.digest();
        if (storedDigest == null || storedDigest.isBlank()) {
            log.error("Snapshot version {} has no stored digest", newSnapshot.snapshotVersion());
            return false;
        }
        String computedDigest = computeSnapshotDigest(newSnapshot);
        if (!computedDigest.equals(storedDigest)) {
            log.error("Snapshot digest verification failed for version {}: expected={}, computed={}",
                    newSnapshot.snapshotVersion(), storedDigest, computedDigest);
            return false;
        }

        // Build the capability lookup index
        Map<String, CapabilityManifest> newIndex = new HashMap<>();
        for (CapabilityManifest manifest : newSnapshot.capabilities()) {
            String key = manifest.metadata().id() + ":" + manifest.metadata().version();
            newIndex.put(key, manifest);
        }

        // Atomically replace the snapshot and index
        currentSnapshot.set(newSnapshot);
        capabilityIndex = Map.copyOf(newIndex);
        lastLoadTime.set(System.currentTimeMillis());

        log.info("Catalog snapshot activated: version={}, capabilities={}, environment={}",
                newSnapshot.snapshotVersion(), newSnapshot.capabilities().size(), environment);
        return true;
    }

    /**
     * Returns the current active snapshot.
     *
     * <p>Each request is pinned to the snapshot version active at the start
     * of processing.</p>
     *
     * @return the current catalog snapshot, or {@code null} if no snapshot
     * has been loaded
     */
    public CatalogSnapshot getCurrentSnapshot() {
        return currentSnapshot.get();
    }

    /**
     * Finds a specific capability by ID and version within the current
     * snapshot.
     *
     * @param id the capability identifier
     * @param version the semantic version
     * @return the matching capability manifest, or empty if not found
     */
    public Optional<CapabilityManifest> findCapability(String id, String version) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        String key = id + ":" + version;
        return Optional.ofNullable(capabilityIndex.get(key));
    }

    /**
     * Returns the current snapshot version for health check purposes
     *
     * @return the current snapshot version, or 0 if no snapshot is loaded
     */
    public long getCurrentSnapshotVersion() {
        CatalogSnapshot snapshot = currentSnapshot.get();
        return snapshot != null ? snapshot.snapshotVersion() : 0;
    }

    /**
     * Checks whether this instance's snapshot is stale — i.e., has not been
     * refreshed within the specified threshold.
     *
     * <p>After exceeding the maximum lag time, the instance should exit the
     * ready state. This method supports health check endpoints in determining
     * readiness.</p>
     *
     * @param maxLagMillis the maximum allowed lag time in milliseconds
     * @return {@code true} if the snapshot is stale (lag exceeds threshold
     * or no snapshot has been loaded)
     */
    public boolean isStale(long maxLagMillis) {
        Long lastLoad = lastLoadTime.get();
        if (lastLoad == 0L) {
            return true; // No snapshot ever loaded
        }
        long lag = System.currentTimeMillis() - lastLoad;
        return lag > maxLagMillis;
    }

    /**
     * Computes the content SHA-256 digest of the snapshot for integrity
     * verification.
     *
     * @param snapshot the snapshot to verify
     * @return the hex-encoded SHA-256 digest
     */
    private String computeSnapshotDigest(CatalogSnapshot snapshot) {
        return CatalogSnapshotDigest.sha256(snapshot);
    }
}
