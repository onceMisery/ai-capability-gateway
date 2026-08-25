package com.ai.gateway.application.controlplane;

import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
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

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Use case for publishing the capability catalog to a target environment
 *
 * <p>Publication must complete in a single logical database transaction:</p>
 * <ol>
 * <li>Validate that the target versions of all capabilities are APPROVED.</li>
 * <li>Generate a new monotonically increasing snapshot version.</li>
 * <li>Freeze all active capabilities and policy references for this
 * environment.</li>
 * <li>Mark the new snapshot as the current version.</li>
 * <li>Write publication audit and notification events.</li>
 * </ol>
 *
 * <p>Each runtime instance receives the publication notification, loads the
 * snapshot from PostgreSQL, builds the retrieval index, verifies the digest,
 * and atomically replaces the in-memory reference. On failure, the instance
 * retains the old snapshot and exits the ready state after exceeding the
 * maximum lag time.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @see CatalogPort
 * @see SnapshotNotifier
 * @since 0.1.0
 */
public final class CatalogPublishUseCase {

    private static final Logger log = LoggerFactory.getLogger(CatalogPublishUseCase.class);

    private final ManifestRepository manifestRepository;
    private final CatalogPort catalogPort;
    private final SnapshotNotifier snapshotNotifier;
    private final LifecycleStateMachine lifecycleStateMachine;
    private final TransactionPort transactionPort;
    private final CapabilityPublicProjectionService publicProjectionService;

    /**
     * Constructs a new CatalogPublishUseCase with the required dependencies.
     *
     * @param manifestRepository the repository for querying manifest lifecycle states
     * @param catalogPort        the port for loading and creating catalog snapshots
     * @param snapshotNotifier   the notifier for propagating snapshot changes to instances
     * @param lifecycleStateMachine the lifecycle state machine guarding manifest transitions
     * @param transactionPort    the transaction boundary for atomic multi-repository publish
     * @throws NullPointerException if any argument is null
     */
    public CatalogPublishUseCase(ManifestRepository manifestRepository,
                                 CatalogPort catalogPort,
                                 SnapshotNotifier snapshotNotifier,
                                 LifecycleStateMachine lifecycleStateMachine,
                                 TransactionPort transactionPort) {
        this(manifestRepository, catalogPort, snapshotNotifier, lifecycleStateMachine,
                transactionPort, null);
    }

    /** Constructs the publication use case with Agent projection governance enabled. */
    public CatalogPublishUseCase(ManifestRepository manifestRepository,
                                 CatalogPort catalogPort,
                                 SnapshotNotifier snapshotNotifier,
                                 LifecycleStateMachine lifecycleStateMachine,
                                 TransactionPort transactionPort,
                                 CapabilityPublicProjectionService publicProjectionService) {
        this.manifestRepository = java.util.Objects.requireNonNull(
                manifestRepository, "manifestRepository must not be null");
        this.catalogPort = java.util.Objects.requireNonNull(
                catalogPort, "catalogPort must not be null");
        this.snapshotNotifier = java.util.Objects.requireNonNull(
                snapshotNotifier, "snapshotNotifier must not be null");
        this.lifecycleStateMachine = java.util.Objects.requireNonNull(
                lifecycleStateMachine, "lifecycleStateMachine must not be null");
        this.transactionPort = java.util.Objects.requireNonNull(
                transactionPort, "transactionPort must not be null");
        this.publicProjectionService = publicProjectionService;
    }

    /**
     * Publishes the capability catalog to the specified environment
     *
     * <p>Publications all APPROVED capability manifests. To publish only a
     * subset, use {@link #publish(String, List)}.</p>
     *
     * @param environment the target environment (e.g., "production")
     * @return the publish result
     * @throws NullPointerException if {@code environment} is null
     */
    public PublishResult publish(String environment) {
        return publish(environment, List.of());
    }

    /**
     * Publishes selected capability manifests to the specified environment
     *
     * <p>The publication is performed in a single logical transaction:</p>
     * <ol>
     * <li>Validate target versions are APPROVED.</li>
     * <li>Generate a new monotonically increasing snapshot version.</li>
     * <li>Freeze the selected capabilities and policy references.</li>
     * <li>Mark the new snapshot as current.</li>
     * <li>Write publish audit and notification event.</li>
     * </ol>
     *
     * <p>If {@code selectedCapabilities} is empty, all APPROVED manifests are
     * published to preserve backward compatibility.</p>
     *
     * @param environment          the target environment (e.g., "production")
     * @param selectedCapabilities the capabilities selected for publication
     * @return the publish result
     * @throws NullPointerException if {@code environment} or
     *                              {@code selectedCapabilities} is null
     */
    public PublishResult publish(String environment,
                                 List<SelectedCapability> selectedCapabilities) {
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(
                selectedCapabilities, "selectedCapabilities must not be null");
        log.info("Publishing catalog to environment: {}, selected={}",
                environment, selectedCapabilities.size());

        // Acquire the environment lock before reading manifests or allocating
        // a version, so concurrent publishers cannot plan duplicate work.
        PublishResult result = transactionPort.inTransaction(() -> {
            catalogPort.lockEnvironmentForPublication(environment);

            CatalogSnapshot currentSnapshot = catalogPort.loadCurrentSnapshot(environment);
            List<String> currentCapabilityKeys = currentSnapshot == null
                    ? List.of()
                    : currentSnapshot.capabilities().stream()
                    .map(manifest -> manifest.metadata().id() + "\u0000"
                            + manifest.metadata().version())
                    .toList();
            List<ManifestRepository.ManifestDetail> publishableDetails =
                    manifestRepository.findAllWithDetails()
                    .stream()
                    .filter(detail -> detail.lifecycle() == CapabilityLifecycle.APPROVED
                            || detail.lifecycle() == CapabilityLifecycle.PUBLISHED)
                    .filter(detail -> !currentCapabilityKeys.contains(
                            detail.manifest().metadata().id() + "\u0000"
                                    + detail.manifest().metadata().version()))
                    .toList();
            List<CapabilityManifest> publishableManifests = publishableDetails.stream()
                    .map(ManifestRepository.ManifestDetail::manifest)
                    .collect(Collectors.toList());
            List<CapabilityManifest> manifestsToPublish = selectedCapabilities.isEmpty()
                    ? publishableManifests
                    : publishableDetails.stream()
                    .filter(m -> selectedCapabilities.stream().anyMatch(
                            s -> s.capabilityId().equals(m.manifest().metadata().id())
                                    && s.version().equals(m.manifest().metadata().version())))
                    .map(ManifestRepository.ManifestDetail::manifest)
                    .collect(Collectors.toList());

            if (manifestsToPublish.isEmpty()) {
                return new PublishResult(false, 0, "No approved manifests to publish");
            }
            if (publicProjectionService != null) {
                List<String> rejectedAgentCapabilities = manifestsToPublish.stream()
                        .filter(manifest -> publicProjectionService.project(manifest).isEmpty())
                        .map(manifest -> manifest.metadata().id() + ":"
                                + manifest.metadata().version())
                        .sorted()
                        .toList();
                if (!rejectedAgentCapabilities.isEmpty()) {
                    return new PublishResult(false, 0,
                            "Agent public projection governance rejected: "
                                    + String.join(", ", rejectedAgentCapabilities));
                }
            }
            for (ManifestRepository.ManifestDetail detail : publishableDetails) {
                if (manifestsToPublish.contains(detail.manifest())
                        && detail.lifecycle() == CapabilityLifecycle.APPROVED) {
                    lifecycleStateMachine.validateTransition(
                            CapabilityLifecycle.APPROVED, CapabilityLifecycle.PUBLISHED);
                }
            }

            long newSnapshotVersion = catalogPort.reserveSnapshotVersion();
            String policyRef = "policy-v" + newSnapshotVersion;
            String digest = computeSnapshotDigest(manifestsToPublish, environment,
                    newSnapshotVersion, policyRef);
            CatalogSnapshot newSnapshot = new CatalogSnapshot(
                    newSnapshotVersion, environment, manifestsToPublish, policyRef, digest);

            catalogPort.saveSnapshot(newSnapshot);
            for (ManifestRepository.ManifestDetail detail : publishableDetails) {
                if (manifestsToPublish.contains(detail.manifest())
                        && detail.lifecycle() == CapabilityLifecycle.APPROVED) {
                    CapabilityManifest manifest = detail.manifest();
                    manifestRepository.updateLifecycle(
                            manifest.metadata().id(), manifest.metadata().version(),
                            CapabilityLifecycle.PUBLISHED);
                }
            }
            catalogPort.recordSnapshotPublication(newSnapshot, "MANIFEST_PUBLISHED");
            return new PublishResult(true, newSnapshotVersion, null);
        });

        if (!result.success()) {
            log.warn("No approved manifests found for publication to {}", environment);
            return result;
        }
        snapshotNotifier.notifySnapshotPublished(result.snapshotVersion());
        log.info("Catalog published successfully: environment={}, snapshotVersion={}",
                environment, result.snapshotVersion());
        return result;
    }

    /**
     * Computes the content SHA-256 digest of the snapshot for integrity
     * verification.
     *
     * @param capabilities    the list of capability manifests in the snapshot
     * @param environment     the target environment
     * @param snapshotVersion the new snapshot version
     * @param policyRef       the policy reference
     * @return the hex-encoded SHA-256 digest
     */
    private String computeSnapshotDigest(List<CapabilityManifest> capabilities,
                                         String environment,
                                         long snapshotVersion,
                                         String policyRef) {
        return CatalogSnapshotDigest.sha256(snapshotVersion, environment, policyRef, capabilities);
    }

    /**
     * A capability selected for publication.
     *
     * @param capabilityId the unique capability identifier
     * @param version      the version to publish
     */
    public record SelectedCapability(
            String capabilityId,
            String version
    ) {
    }

    /**
     * The result of a catalog publish operation.
     *
     * @param success         whether the publish succeeded
     * @param snapshotVersion the new snapshot version; 0 on failure
     * @param error           the error message; null on success
     */
    public record PublishResult(
            boolean success,
            long snapshotVersion,
            String error
    ) {
    }
}
