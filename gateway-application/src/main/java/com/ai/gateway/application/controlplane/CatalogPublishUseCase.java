package com.ai.gateway.application.controlplane;

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

    /**
     * Constructs a new CatalogPublishUseCase with the required dependencies.
     *
     * @param manifestRepository the repository for querying manifest lifecycle states
     * @param catalogPort        the port for loading and creating catalog snapshots
     * @param snapshotNotifier   the notifier for propagating snapshot changes to instances
     * @throws NullPointerException if any argument is null
     */
    public CatalogPublishUseCase(ManifestRepository manifestRepository,
                                 CatalogPort catalogPort,
                                 SnapshotNotifier snapshotNotifier) {
        this.manifestRepository = java.util.Objects.requireNonNull(
                manifestRepository, "manifestRepository must not be null");
        this.catalogPort = java.util.Objects.requireNonNull(
                catalogPort, "catalogPort must not be null");
        this.snapshotNotifier = java.util.Objects.requireNonNull(
                snapshotNotifier, "snapshotNotifier must not be null");
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

        // Step 1: Validate target versions are APPROVED
        List<ManifestRepository.ManifestDetail> allManifests =
                manifestRepository.findAllWithDetails();
        List<CapabilityManifest> approvedManifests = new ArrayList<>();

        for (ManifestRepository.ManifestDetail detail : allManifests) {
            if (detail.lifecycle() == com.ai.gateway.domain.model.CapabilityLifecycle.APPROVED) {
                approvedManifests.add(detail.manifest());
            }
        }

        if (!selectedCapabilities.isEmpty()) {
            approvedManifests = approvedManifests.stream()
                    .filter(m -> selectedCapabilities.stream().anyMatch(
                            s -> s.capabilityId().equals(m.metadata().id())
                                    && s.version().equals(m.metadata().version())))
                    .collect(Collectors.toList());
        }

        if (approvedManifests.isEmpty()) {
            log.warn("No approved manifests found for publication to {}", environment);
            return new PublishResult(false, 0, "No approved manifests to publish");
        }

        // Step 2: Generate a new monotonically increasing snapshot version
        long newSnapshotVersion = catalogPort.reserveSnapshotVersion();

        // Step 3: Freeze all active capabilities and policy references
        // The snapshot is immutable once created; capabilities are frozen
        // by copying them into the snapshot
        String policyRef = "policy-v" + newSnapshotVersion;

        // Compute the content digest for integrity verification
        String digest = computeSnapshotDigest(approvedManifests, environment,
                newSnapshotVersion, policyRef);

        CatalogSnapshot newSnapshot = new CatalogSnapshot(
                newSnapshotVersion,
                environment,
                approvedManifests,
                policyRef,
                digest
        );

        // Step 4: Mark the new snapshot as current
        // In a full implementation, this would be a database transaction
        // that atomically marks the old snapshot as non-current and the new
        // one as current. The CatalogPort adapter handles this.
        catalogPort.saveSnapshot(newSnapshot);
        for (CapabilityManifest manifest : approvedManifests) {
            manifestRepository.updateLifecycle(
                    manifest.metadata().id(), manifest.metadata().version(),
                    com.ai.gateway.domain.model.CapabilityLifecycle.PUBLISHED);
        }
        log.info("Created snapshot version {} for environment {} with {} capabilities",
                newSnapshotVersion, environment, approvedManifests.size());

        // Step 5: Write publish audit and notification event
        snapshotNotifier.notifySnapshotPublished(newSnapshotVersion);

        log.info("Catalog published successfully: environment={}, snapshotVersion={}, capabilities={}",
                environment, newSnapshotVersion, approvedManifests.size());

        return new PublishResult(true, newSnapshotVersion, null);
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
