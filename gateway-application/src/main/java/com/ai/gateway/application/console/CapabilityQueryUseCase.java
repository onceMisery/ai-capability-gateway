package com.ai.gateway.application.console;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilitySummary;
import com.ai.gateway.domain.model.SnapshotSummary;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.ManifestRepository.ManifestDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Use case for querying capability manifests and catalog snapshots
 * from the admin console.
 *
 * <p>Provides read-only views of:</p>
 * <ul>
 * <li>All capability manifests with their lifecycle states and metadata.</li>
 * <li>Catalog snapshot history with summaries.</li>
 * <li>Individual manifest details.</li>
 * </ul>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @since 0.1.0
 */
public final class CapabilityQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(CapabilityQueryUseCase.class);

    private final ManifestRepository manifestRepository;
    private final CatalogPort catalogPort;

    /**
     * Constructs a new CapabilityQueryUseCase.
     *
     * @param manifestRepository the manifest repository
     * @param catalogPort the catalog port for snapshot queries
     */
    public CapabilityQueryUseCase(ManifestRepository manifestRepository, CatalogPort catalogPort) {
        this.manifestRepository = Objects.requireNonNull(manifestRepository);
        this.catalogPort = Objects.requireNonNull(catalogPort);
    }

    /**
     * Lists all capability manifests as summaries.
     *
     * <p>Each summary includes the manifest metadata, lifecycle state,
     * and the snapshot versions that include this capability.</p>
     *
     * @return the list of capability summaries; never {@code null}
     */
    public List<CapabilitySummary> listCapabilities() {
        List<ManifestDetail> details = manifestRepository.findAllWithDetails();

        // Build a map of capabilityId -> snapshot versions
        Map<String, List<Long>> snapshotVersionMap = buildSnapshotVersionMap();

        return details.stream()
                .map(d -> toSummary(d, snapshotVersionMap))
                .collect(Collectors.toList());
    }

    /**
     * Gets the full manifest detail for a specific capability.
     *
     * @param capabilityId the capability identifier
     * @param version the capability version
     * @return the full manifest, or {@code null} if not found
     */
    public CapabilityManifest getCapabilityDetail(String capabilityId, String version) {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(version, "version must not be null");
        return manifestRepository.findByIdAndVersion(capabilityId, version).orElse(null);
    }

    /**
     * Lists snapshot summaries for the given environment.
     *
     * @param environment the target environment
     * @param limit the maximum number of summaries
     * @return the snapshot summaries; never {@code null}
     */
    public List<SnapshotSummary> listSnapshots(String environment, int limit) {
        Objects.requireNonNull(environment, "environment must not be null");
        return catalogPort.listSnapshots(environment, limit);
    }

    private Map<String, List<Long>> buildSnapshotVersionMap() {
        Map<String, List<Long>> map = new HashMap<>();
        try {
            // Load snapshots from common environments
            for (String env : List.of("production", "staging")) {
                List<SnapshotSummary> snapshots = catalogPort.listSnapshots(env, 50);
                for (SnapshotSummary snapshot : snapshots) {
                    // We don't have a direct way to get items per snapshot
                    // from the summary, so we load the full snapshot
                    try {
                        var fullSnapshot = catalogPort.loadSnapshot(snapshot.snapshotVersion());
                        for (CapabilityManifest cap : fullSnapshot.capabilities()) {
                            String key = cap.metadata().id() + ":" + cap.metadata().version();
                            map.computeIfAbsent(key, k -> new ArrayList<>())
                                    .add(snapshot.snapshotVersion());
                        }
                    } catch (Exception e) {
                        log.debug("Could not load snapshot version {}: {}",
                                snapshot.snapshotVersion(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not build snapshot version map: {}", e.getMessage());
        }
        return map;
    }

    private CapabilitySummary toSummary(ManifestDetail detail,
                                         Map<String, List<Long>> snapshotVersionMap) {
        CapabilityManifest manifest = detail.manifest();
        String key = manifest.metadata().id() + ":" + manifest.metadata().version();
        List<Long> versions = snapshotVersionMap.getOrDefault(key, List.of());

        return new CapabilitySummary(
                manifest.metadata().id(),
                manifest.metadata().version(),
                manifest.spec().displayName(),
                manifest.spec().description(),
                manifest.spec().risk(),
                detail.lifecycle(),
                manifest.metadata().tags(),
                manifest.metadata().owner().team(),
                manifest.metadata().owner().contact(),
                detail.sha256Digest(),
                detail.updatedAt(),
                versions
        );
    }
}
