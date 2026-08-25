package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.SnapshotNotifier;
import com.ai.gateway.domain.port.TransactionPort;
import com.ai.gateway.domain.service.LifecycleStateMachine;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Use case for emergency suspension of a capability.
 *
 * <p>Emergency suspension immediately removes a capability from the active
 * routing set. The suspension:</p>
 * <ol>
 * <li>Transitions the capability lifecycle to SUSPENDED.</li>
 * <li>Generates a new snapshot excluding the suspended capability.</li>
 * <li>Propagates the change via high-priority notification.</li>
 * </ol>
 *
 * <p>Suspended capabilities retain audit and recovery capabilities. Restoration
 * requires re-validation and a new snapshot; it must not be done in-place on
 * the original snapshot.</p>
 *
 * <p>The runtime plane queries the local suspension table's latest version
 * before actually calling the Provider. For security emergency suspensions,
 * a lightweight database re-check may be added.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @see ManifestRepository
 * @see CatalogPort
 * @see SnapshotNotifier
 * @since 0.1.0
 */
public final class CapabilitySuspendUseCase {

    private static final Logger log = LoggerFactory.getLogger(CapabilitySuspendUseCase.class);

    private final ManifestRepository manifestRepository;
    private final CatalogPort catalogPort;
    private final SnapshotNotifier snapshotNotifier;
    private final String environment;
    private final LifecycleStateMachine lifecycleStateMachine;
    private final TransactionPort transactionPort;

    /**
     * Constructs a new CapabilitySuspendUseCase with the required dependencies.
     *
     * @param manifestRepository the repository for updating capability lifecycle
     * @param catalogPort        the port for loading the current snapshot
     * @param snapshotNotifier   the notifier for high-priority snapshot propagation
     * @param lifecycleStateMachine the lifecycle state machine guarding manifest transitions
     * @throws NullPointerException if any argument is null
     */
    public CapabilitySuspendUseCase(ManifestRepository manifestRepository,
                                    CatalogPort catalogPort,
                                    SnapshotNotifier snapshotNotifier,
                                    String environment,
                                    LifecycleStateMachine lifecycleStateMachine,
                                    TransactionPort transactionPort) {
        this.manifestRepository = java.util.Objects.requireNonNull(
                manifestRepository, "manifestRepository must not be null");
        this.catalogPort = java.util.Objects.requireNonNull(
                catalogPort, "catalogPort must not be null");
        this.snapshotNotifier = java.util.Objects.requireNonNull(
                snapshotNotifier, "snapshotNotifier must not be null");
        this.environment = java.util.Objects.requireNonNull(
                environment, "environment must not be null");
        this.lifecycleStateMachine = java.util.Objects.requireNonNull(
                lifecycleStateMachine, "lifecycleStateMachine must not be null");
        this.transactionPort = java.util.Objects.requireNonNull(
                transactionPort, "transactionPort must not be null");
    }

    /**
     * Suspends a capability immediately.
     *
     * <p>The suspension generates a new snapshot that excludes the suspended
     * capability and propagates via high-priority notification. The runtime
     * plane queries the local suspension table before calling the Provider.</p>
     *
     * @param capabilityId the capability identifier to suspend
     * @param reason       the suspension reason (for audit)
     * @param operator     the operator performing the suspension
     * @return the suspension result
     * @throws NullPointerException if any argument is null
     */
    public SuspendResult suspend(String capabilityId, String reason, String operator) {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(reason, "reason must not be null");
        java.util.Objects.requireNonNull(operator, "operator must not be null");
        log.warn("Emergency suspension requested: capabilityId={}, reason={}, operator={}",
                capabilityId, reason, operator);

        SuspendResult result;
        try {
            result = transactionPort.inTransaction(() -> {
                catalogPort.lockEnvironmentForPublication(environment);
                CatalogSnapshot currentSnapshot = catalogPort.loadCurrentSnapshot(environment);
                if (currentSnapshot == null) {
                    return new SuspendResult(false, 0, "当前没有可操作的活动快照。");
                }

                String suspendedVersion = null;
                List<CapabilityManifest> remainingCapabilities = new ArrayList<>();
                for (CapabilityManifest manifest : currentSnapshot.capabilities()) {
                    if (manifest.metadata().id().equals(capabilityId)) {
                        suspendedVersion = manifest.metadata().version();
                    } else {
                        remainingCapabilities.add(manifest);
                    }
                }
                if (suspendedVersion == null) {
                    return new SuspendResult(false, 0,
                            "当前活动快照中未找到能力「" + capabilityId + "」。");
                }

                lifecycleStateMachine.validateTransition(
                        CapabilityLifecycle.PUBLISHED, CapabilityLifecycle.SUSPENDED);
                manifestRepository.updateLifecycle(capabilityId, suspendedVersion,
                        CapabilityLifecycle.SUSPENDED);
                log.info("Capability {} version {} transitioned to SUSPENDED",
                        capabilityId, suspendedVersion);

                long newSnapshotVersion = catalogPort.reserveSnapshotVersion();
                List<CapabilityManifest> remainingForTransaction = List.copyOf(remainingCapabilities);
                String policyRef = currentSnapshot.policyRef()
                        + "-suspend-" + newSnapshotVersion;
                String digest = computeSuspendDigest(
                        remainingForTransaction, newSnapshotVersion, policyRef);
                CatalogSnapshot snapshot = new CatalogSnapshot(
                        newSnapshotVersion, environment, remainingForTransaction, policyRef, digest);
                catalogPort.saveSnapshot(snapshot);
                catalogPort.recordSnapshotPublication(snapshot, "MANIFEST_SUSPENDED");
                return new SuspendResult(true, newSnapshotVersion, null);
            });
        } catch (Exception e) {
            log.error("Failed to suspend capability {}: {}", capabilityId, e.getMessage());
            return new SuspendResult(false, 0, e.getMessage());
        }

        if (!result.success()) {
            return result;
        }

        log.info("Created suspension snapshot version {}", result.snapshotVersion());

        // Step 4: Propagate via high-priority notification after commit
        snapshotNotifier.notifySnapshotSuspended(result.snapshotVersion());

        log.warn("Emergency suspension complete: capabilityId={}, snapshotVersion={}, operator={}",
                capabilityId, result.snapshotVersion(), operator);

        return result;
    }

    /**
     * Computes the content SHA-256 digest for the suspension snapshot.
     *
     * @param capabilities    the remaining capabilities after suspension
     * @param snapshotVersion the new snapshot version
     * @param policyRef       the new policy reference
     * @return the hex-encoded SHA-256 digest
     */
    private String computeSuspendDigest(List<CapabilityManifest> capabilities,
                                        long snapshotVersion,
                                        String policyRef) {
        return CatalogSnapshotDigest.sha256(snapshotVersion, environment, policyRef, capabilities);
    }

    /**
     * The result of an emergency suspension operation.
     *
     * @param success         whether the suspension succeeded
     * @param snapshotVersion the new snapshot version; 0 on failure
     * @param error           the error message; null on success
     */
    public record SuspendResult(
            boolean success,
            long snapshotVersion,
            String error
    ) {
    }
}
