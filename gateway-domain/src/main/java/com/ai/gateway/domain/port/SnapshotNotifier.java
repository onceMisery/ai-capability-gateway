package com.ai.gateway.domain.port;

/**
 * Port for notifying runtime instances of snapshot changes.
 *
 * <p>(Publication) specifies that publication must complete in
 * a single database transaction, including writing publication audit and
 * notification events. Each runtime instance receives the notification,
 * loads the snapshot from PostgreSQL, builds the retrieval index, and
 * verifies the digest. On success, the in-memory reference is atomically
 * replaced. On failure, the instance retains the old snapshot and exits
 * the ready state after exceeding the maximum lag time.</p>
 *
 * <p>(Rollback and Emergency Suspension): rollback copies a
 * historical snapshot's content into a new snapshot version and
 * publishes it via notification. Emergency suspension generates a new
 * snapshot and propagates via high-priority notification. The runtime
 * plane queries the local suspension table's latest version before
 * actually calling the Provider; for security emergency suspensions, a
 * lightweight database re-check may be added.</p>
 *
 * <p>Adapters implementing this port publish notifications via a
 * mechanism such as PostgreSQL LISTEN/NOTIFY, a message queue, or a
 * polling mechanism. The port is a pure abstraction with no framework
 * dependencies.</p>
 *
 * @since 0.1.0
 */
public interface SnapshotNotifier {

    /**
     * Notifies runtime instances that a new snapshot has been published.
     *
     * <p>: after the publication transaction commits, this
     * notification triggers each instance to load the new snapshot, build
     * the retrieval index, verify the digest, and atomically replace the
     * in-memory reference.</p>
     *
     * @param snapshotVersion the newly published snapshot version
     */
    void notifySnapshotPublished(long snapshotVersion);

    /**
     * Notifies runtime instances that a snapshot has been suspended.
     *
     * <p>: emergency suspension generates a new snapshot and
     * propagates via high-priority notification. The runtime plane must
     * query the local suspension table's latest version before calling
     * the Provider.</p>
     *
     * @param snapshotVersion the suspended snapshot version
     */
    void notifySnapshotSuspended(long snapshotVersion);
}
