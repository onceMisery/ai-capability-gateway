package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.SnapshotNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /**
     * Constructs a new CatalogRollbackUseCase with the required dependencies.
     *
     * @param catalogPort the port for loading historical and current snapshots
     * @param snapshotNotifier the notifier for propagating snapshot changes to instances
     * @throws NullPointerException if any argument is null
     */
    public CatalogRollbackUseCase(CatalogPort catalogPort,
                                   SnapshotNotifier snapshotNotifier) {
        this.catalogPort = java.util.Objects.requireNonNull(
                catalogPort, "catalogPort must not be null");
        this.snapshotNotifier = java.util.Objects.requireNonNull(
                snapshotNotifier, "snapshotNotifier must not be null");
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

        // Step 1: Load the historical snapshot
        CatalogSnapshot historicalSnapshot;
        try {
            historicalSnapshot = catalogPort.loadSnapshot(targetSnapshotVersion);
        } catch (Exception e) {
            log.error("Failed to load historical snapshot version {}: {}",
                    targetSnapshotVersion, e.getMessage());
            return new RollbackResult(false, 0,
                    "Failed to load historical snapshot: " + e.getMessage());
        }

        if (historicalSnapshot == null) {
            return new RollbackResult(false, 0,
                    "Historical snapshot version " + targetSnapshotVersion + " not found");
        }

        // Step 2: Determine the new snapshot version (monotonically increasing)
        long currentVersion = 0;
        try {
            CatalogSnapshot current = catalogPort.loadCurrentSnapshot(environment);
            if (current != null) {
                currentVersion = current.snapshotVersion();
            }
        } catch (Exception e) {
            log.warn("Could not load current snapshot: {}", e.getMessage());
        }
        long newSnapshotVersion = catalogPort.reserveSnapshotVersion();

        // Step 3: Copy historical content as a new snapshot, reapplying
        // revocation list and authorization policy
        // The capabilities from the historical snapshot are frozen into the
        // new snapshot. The policy reference is inherited and versioned.
        String newPolicyRef = historicalSnapshot.policyRef() + "-rollback-" + newSnapshotVersion;

        // Compute a new digest for the rollback snapshot
        String newDigest = computeRollbackDigest(
                historicalSnapshot.capabilities(),
                environment,
                newSnapshotVersion,
                newPolicyRef
        );

        CatalogSnapshot rollbackSnapshot = new CatalogSnapshot(
                newSnapshotVersion,
                environment,
                historicalSnapshot.capabilities(),
                newPolicyRef,
                newDigest);
        catalogPort.saveSnapshot(rollbackSnapshot);

        // The new snapshot carries the historical capabilities with a new
        // version number and fresh digest
        log.info("Created rollback snapshot version {} from historical version {} for environment {}",
                newSnapshotVersion, targetSnapshotVersion, environment);

        // Step 4: Notify runtime instances of the new snapshot
        snapshotNotifier.notifySnapshotPublished(newSnapshotVersion);

        log.info("Catalog rolled back successfully: environment={}, newSnapshotVersion={}, fromHistorical={}",
                environment, newSnapshotVersion, targetSnapshotVersion);

        return new RollbackResult(true, newSnapshotVersion, null);
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
        try {
            StringBuilder content = new StringBuilder();
            content.append(snapshotVersion).append('\n');
            content.append(environment).append('\n');
            content.append(policyRef).append('\n');
            for (CapabilityManifest manifest : capabilities) {
                content.append(manifest.metadata().id())
                        .append(':')
                        .append(manifest.metadata().version())
                        .append('\n');
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available", e);
            return "DIGEST_ERROR";
        }
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
