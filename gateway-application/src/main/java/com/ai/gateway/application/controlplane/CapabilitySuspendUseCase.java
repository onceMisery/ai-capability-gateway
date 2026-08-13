package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.SnapshotNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /**
     * Constructs a new CapabilitySuspendUseCase with the required dependencies.
     *
     * @param manifestRepository the repository for updating capability lifecycle
     * @param catalogPort the port for loading the current snapshot
     * @param snapshotNotifier the notifier for high-priority snapshot propagation
     * @throws NullPointerException if any argument is null
     */
    public CapabilitySuspendUseCase(ManifestRepository manifestRepository,
                                     CatalogPort catalogPort,
                                     SnapshotNotifier snapshotNotifier,
                                     String environment) {
        this.manifestRepository = java.util.Objects.requireNonNull(
                manifestRepository, "manifestRepository must not be null");
        this.catalogPort = java.util.Objects.requireNonNull(
                catalogPort, "catalogPort must not be null");
        this.snapshotNotifier = java.util.Objects.requireNonNull(
                snapshotNotifier, "snapshotNotifier must not be null");
        this.environment = java.util.Objects.requireNonNull(
                environment, "environment must not be null");
    }

    /**
     * Suspends a capability immediately.
     *
     * <p>The suspension generates a new snapshot that excludes the suspended
     * capability and propagates via high-priority notification. The runtime
     * plane queries the local suspension table before calling the Provider.</p>
     *
     * @param capabilityId the capability identifier to suspend
     * @param reason the suspension reason (for audit)
     * @param operator the operator performing the suspension
     * @return the suspension result
     * @throws NullPointerException if any argument is null
     */
    public SuspendResult suspend(String capabilityId, String reason, String operator) {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(reason, "reason must not be null");
        java.util.Objects.requireNonNull(operator, "operator must not be null");
        log.warn("Emergency suspension requested: capabilityId={}, reason={}, operator={}",
                capabilityId, reason, operator);

        // Step 1: Find the capability in the current snapshot to determine its version
        CatalogSnapshot currentSnapshot;
        try {
            currentSnapshot = catalogPort.loadCurrentSnapshot(environment);
        } catch (Exception e) {
            log.error("Failed to load current snapshot for suspension: {}", e.getMessage());
            return new SuspendResult(false, 0,
                    "Failed to load current snapshot: " + e.getMessage());
        }

        if (currentSnapshot == null) {
            return new SuspendResult(false, 0, "No active snapshot found");
        }

        // Find the capability to suspend and build the new capability list
        String suspendedVersion = null;
        List<CapabilityManifest> remainingCapabilities = new ArrayList<>();
        for (CapabilityManifest manifest : currentSnapshot.capabilities()) {
            if (manifest.metadata().id().equals(capabilityId)) {
                suspendedVersion = manifest.metadata().version();
                // Skip this capability — it is being suspended
            } else {
                remainingCapabilities.add(manifest);
            }
        }

        if (suspendedVersion == null) {
            log.warn("Capability {} not found in current snapshot; may already be suspended",
                    capabilityId);
            return new SuspendResult(false, 0,
                    "Capability " + capabilityId + " not found in current snapshot");
        }

        // Step 2: Transition the capability lifecycle to SUSPENDED
        manifestRepository.updateLifecycle(capabilityId, suspendedVersion,
                CapabilityLifecycle.SUSPENDED);
        log.info("Capability {} version {} transitioned to SUSPENDED", capabilityId, suspendedVersion);

        // Step 3: Generate a new snapshot excluding the suspended capability
        long newSnapshotVersion = catalogPort.reserveSnapshotVersion();
        String policyRef = currentSnapshot.policyRef() + "-suspend-" + newSnapshotVersion;
        String digest = computeSuspendDigest(remainingCapabilities, newSnapshotVersion, policyRef);

        CatalogSnapshot suspendedSnapshot = new CatalogSnapshot(
                newSnapshotVersion,
                environment,
                remainingCapabilities,
                policyRef,
                digest);
        catalogPort.saveSnapshot(suspendedSnapshot);

        log.info("Created suspension snapshot version {} with {} remaining capabilities",
                newSnapshotVersion, remainingCapabilities.size());

        // Step 4: Propagate via high-priority notification
        snapshotNotifier.notifySnapshotSuspended(newSnapshotVersion);

        log.warn("Emergency suspension complete: capabilityId={}, snapshotVersion={}, operator={}",
                capabilityId, newSnapshotVersion, operator);

        return new SuspendResult(true, newSnapshotVersion, null);
    }

    /**
     * Computes the content SHA-256 digest for the suspension snapshot.
     *
     * @param capabilities the remaining capabilities after suspension
     * @param snapshotVersion the new snapshot version
     * @param policyRef the new policy reference
     * @return the hex-encoded SHA-256 digest
     */
    private String computeSuspendDigest(List<CapabilityManifest> capabilities,
                                         long snapshotVersion,
                                         String policyRef) {
        try {
            StringBuilder content = new StringBuilder();
            content.append(snapshotVersion).append('\n');
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
     * The result of an emergency suspension operation.
     *
     * @param success whether the suspension succeeded
     * @param snapshotVersion the new snapshot version; 0 on failure
     * @param error the error message; null on success
     */
    public record SuspendResult(
            boolean success,
            long snapshotVersion,
            String error
    ) {
    }
}
