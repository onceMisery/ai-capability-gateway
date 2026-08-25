package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.SnapshotNotifier;
import com.ai.gateway.domain.port.TransactionPort;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Use case for rolling back the capability catalog to a historical snapshot
 * version.
 *
 * <p>Rollback copies a historical snapshot's content into a new snapshot
 * version; it does not modify history. The rollback process:</p>
 * <ol>
 * <li>Loads the historical snapshot by its version number.</li>
 * <li>Creates a new snapshot with the same capabilities and policy
 * references, but a new monotonically increasing version.</li>
 * <li>Reapplies the revocation list and authorization policy.</li>
 * <li>Notifies runtime instances of the new snapshot.</li>
 * </ol>
 *
 * <p>The rollback snapshot inherits the capabilities from the historical
 * version but gets a fresh digest and version number. This ensures that
 * instances can verify integrity and that the rollback is auditable as a
 * new publication event.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @see CatalogPort
 * @see SnapshotNotifier
 * @since 0.1.0
 */
public final class CatalogRollbackUseCase {

    private static final Logger log = LoggerFactory.getLogger(CatalogRollbackUseCase.class);

    private final CatalogPort catalogPort;
    private final SnapshotNotifier snapshotNotifier;
    private final TransactionPort transactionPort;

    /**
     * Constructs a new CatalogRollbackUseCase with the required dependencies.
     *
     * @param catalogPort the port for loading historical and current snapshots
     * @param snapshotNotifier the notifier for propagating snapshot changes to instances
     * @throws NullPointerException if any argument is null
     */
    public CatalogRollbackUseCase(CatalogPort catalogPort,
                                   SnapshotNotifier snapshotNotifier,
                                   TransactionPort transactionPort) {
        this.catalogPort = java.util.Objects.requireNonNull(
                catalogPort, "catalogPort must not be null");
        this.snapshotNotifier = java.util.Objects.requireNonNull(
                snapshotNotifier, "snapshotNotifier must not be null");
        this.transactionPort = java.util.Objects.requireNonNull(
                transactionPort, "transactionPort must not be null");
    }

    /**
     * Rolls back the catalog to the specified historical snapshot version
     *
     * <p>The rollback copies the historical snapshot's content as a new
     * snapshot, reapplies the revocation list and authorization policy,
     * and notifies runtime instances.</p>
     *
     * @param targetSnapshotVersion the historical snapshot version to roll back to
     * @param environment the target environment
     * @return the rollback result
     * @throws NullPointerException if {@code environment} is null
     */
    public RollbackResult rollback(long targetSnapshotVersion, String environment) {
        java.util.Objects.requireNonNull(environment, "environment must not be null");
        log.info("Rolling back catalog to snapshot version {} for environment {}",
                targetSnapshotVersion, environment);

        // Lock before loading history or allocating a replacement version.
        CatalogSnapshot rollbackSnapshot;
        try {
            rollbackSnapshot = transactionPort.inTransaction(() -> {
                catalogPort.lockEnvironmentForPublication(environment);
                CatalogSnapshot historicalSnapshot = catalogPort.loadSnapshot(targetSnapshotVersion);
                if (historicalSnapshot == null) {
                    throw new IllegalArgumentException(
                            "未找到历史快照版本 " + targetSnapshotVersion + "。");
                }
                long newSnapshotVersion = catalogPort.reserveSnapshotVersion();
                String newPolicyRef = historicalSnapshot.policyRef()
                        + "-rollback-" + newSnapshotVersion;
                String newDigest = computeRollbackDigest(
                        historicalSnapshot.capabilities(), environment,
                        newSnapshotVersion, newPolicyRef);
                CatalogSnapshot snapshot = new CatalogSnapshot(
                        newSnapshotVersion, environment, historicalSnapshot.capabilities(),
                        newPolicyRef, newDigest);
                catalogPort.saveSnapshot(snapshot);
                catalogPort.recordSnapshotPublication(snapshot, "CATALOG_ROLLED_BACK");
                return snapshot;
            });
        } catch (Exception e) {
            log.error("Failed to roll back snapshot version {}: {}",
                    targetSnapshotVersion, e.getMessage());
            return new RollbackResult(false, 0, e.getMessage());
        }

        // The new snapshot carries the historical capabilities with a new
        // version number and fresh digest
        log.info("Created rollback snapshot version {} from historical version {} for environment {}",
                rollbackSnapshot.snapshotVersion(), targetSnapshotVersion, environment);

        // Step 4: Notify runtime instances of the new snapshot
        snapshotNotifier.notifySnapshotPublished(rollbackSnapshot.snapshotVersion());

        log.info("Catalog rolled back successfully: environment={}, newSnapshotVersion={}, fromHistorical={}",
                environment, rollbackSnapshot.snapshotVersion(), targetSnapshotVersion);

        return new RollbackResult(true, rollbackSnapshot.snapshotVersion(), null);
    }

    /**
     * Computes the content SHA-256 digest for the rollback snapshot.
     *
     * @param capabilities the capabilities from the historical snapshot
     * @param environment the target environment
     * @param snapshotVersion the new snapshot version
     * @param policyRef the new policy reference
     * @return the hex-encoded SHA-256 digest
     */
    private String computeRollbackDigest(List<CapabilityManifest> capabilities,
                                          String environment,
                                          long snapshotVersion,
                                          String policyRef) {
        return CatalogSnapshotDigest.sha256(snapshotVersion, environment, policyRef, capabilities);
    }

    /**
     * The result of a catalog rollback operation.
     *
     * @param success whether the rollback succeeded
     * @param snapshotVersion the new snapshot version created by the rollback; 0 on failure
     * @param error the error message; null on success
     */
    public record RollbackResult(
            boolean success,
            long snapshotVersion,
            String error
    ) {
    }
}
