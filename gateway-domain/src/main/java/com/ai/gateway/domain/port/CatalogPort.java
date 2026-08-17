package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.SnapshotSummary;

import java.util.List;
import java.util.Optional;

/**
 * Port for loading and querying the capability catalog snapshot.
 *
 * <p>Specifies that publication must complete in a single
 * database transaction, producing a monotonically increasing
 * {@code snapshotVersion}. Each runtime instance receives the publication
 * notification, loads the snapshot from PostgreSQL, builds the retrieval
 * index, and verifies the digest. On success, the in-memory reference is
 * atomically replaced.</p>
 *
 * <p>Each request is pinned to the snapshot version active at the start of
 * processing. Rollback copies a historical snapshot's content
 * into a new snapshot version; it does not modify history.</p>
 *
 * <p>Adapters implementing this port typically read from PostgreSQL and
 * cache the snapshot in memory. The port is a pure abstraction with no
 * framework dependencies.</p>
 *
 * @see CatalogSnapshot
 * @see CapabilityManifest
 * @since 0.1.0
 */
public interface CatalogPort {

    /**
     * Serializes control-plane lifecycle changes for an environment.
     * Persistent adapters acquire a transaction-scoped lock; test and
     * in-memory adapters may keep the default no-op implementation.
     *
     * @param environment the environment being changed
     */
    default void lockEnvironmentForPublication(String environment) {
        // No-op for adapters without a shared transactional store.
    }

    /**
     * Reserves the next globally unique snapshot version.
     *
     * <p>Persistent adapters must use a database sequence or an equivalent
     * atomic allocator. Callers must not derive a version by reading the
     * current snapshot and adding one.</p>
     *
     * @return a monotonically increasing snapshot version
     */
    long reserveSnapshotVersion();

    /**
     * Loads the current active snapshot for the given environment.
     *
     * <p>: the snapshot marked as the current version for this
     * environment is returned. The snapshot is immutable once published.</p>
     *
     * @param environment the target environment (e.g., "production")
     * @return the current catalog snapshot; never {@code null}
     */
    CatalogSnapshot loadCurrentSnapshot(String environment);

    /**
     * Loads a specific historical snapshot by its version number.
     *
     * <p>: rollback copies a historical snapshot's content into
     * a new snapshot version; it does not modify history. This method
     * retrieves the original historical snapshot for inspection or
     * rollback purposes.</p>
     *
     * @param snapshotVersion the monotonically increasing snapshot version
     * @return the catalog snapshot at the given version; never {@code null}
     */
    CatalogSnapshot loadSnapshot(long snapshotVersion);

    /**
     * Finds a specific capability by ID and version within the current
     * snapshot.
     *
     * <p>The returned manifest is the exact published content, verifiable
     * by its SHA-256 digest. The same {@code id + version} content cannot
     * be overwritten.</p>
     *
     * @param capabilityId the globally stable capability identifier
     * @param version the semantic version string
     * @return the matching capability manifest, or empty if not found
     */
    Optional<CapabilityManifest> findCapability(String capabilityId, String version);

    /**
     * Persists a new catalog snapshot and marks it as ACTIVE.
     *
     * <p>Previous ACTIVE snapshots for the same environment are marked
     * as SUPERSEDED. This operation only changes snapshot tables; lifecycle,
     * audit and outbox side effects are explicit operations owned by the
     * application transaction.</p>
     *
     * @param snapshot the snapshot to persist
     */
    void saveSnapshot(CatalogSnapshot snapshot);

    /**
     * Records the publication event associated with a persisted snapshot.
     * The call is explicit so saving a snapshot has no hidden lifecycle,
     * audit, or outbox side effects and can be composed by the application
     * transaction owner.
     */
    void recordSnapshotPublication(CatalogSnapshot snapshot, String eventType);

    /**
     * Lists snapshot summaries for the given environment, ordered by version
     * descending (newest first).
     *
     * <p>Used by the admin console to display snapshot history. The returned
     * summaries do not include the full snapshot content.</p>
     *
     * @param environment the target environment (e.g., "production")
     * @param limit the maximum number of summaries to return
     * @return the snapshot summaries; never {@code null}
     */
    List<SnapshotSummary> listSnapshots(String environment, int limit);
}
