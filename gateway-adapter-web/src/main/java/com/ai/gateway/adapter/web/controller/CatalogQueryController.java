package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.console.CapabilityQueryUseCase;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilitySummary;
import com.ai.gateway.domain.model.SnapshotSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST controller for querying capability manifests and catalog snapshots
 * from the admin console.
 *
 * <p>Exposes read-only endpoints under {@code /admin/v1} for:</p>
 * <ul>
 * <li>Capability manifest listing and detail queries.</li>
 * <li>Catalog snapshot history queries.</li>
 * </ul>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/admin/v1")
public class CatalogQueryController {

    private static final Logger log = LoggerFactory.getLogger(CatalogQueryController.class);

    private final CapabilityQueryUseCase capabilityQueryUseCase;

    /**
     * Constructs a new CatalogQueryController.
     *
     * @param capabilityQueryUseCase the capability query use case
     */
    public CatalogQueryController(CapabilityQueryUseCase capabilityQueryUseCase) {
        this.capabilityQueryUseCase = Objects.requireNonNull(capabilityQueryUseCase);
    }

    /**
     * GET /admin/v1/capabilities
     *
     * <p>Returns all capability manifests as summaries.</p>
     */
    @GetMapping("/capabilities")
    public ResponseEntity<List<CapabilitySummary>> listCapabilities() {
        List<CapabilitySummary> capabilities = capabilityQueryUseCase.listCapabilities();
        return ResponseEntity.ok(capabilities);
    }

    /**
     * GET /admin/v1/capabilities/{id}/versions/{version}
     *
     * <p>Returns the full manifest detail for a specific capability.</p>
     */
    @GetMapping("/capabilities/{id}/versions/{version}")
    public ResponseEntity<?> getCapabilityDetail(
            @PathVariable String id,
            @PathVariable String version) {
        CapabilityManifest manifest = capabilityQueryUseCase.getCapabilityDetail(id, version);
        if (manifest == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(manifest);
    }

    /**
     * GET /admin/v1/releases
     *
     * <p>Returns snapshot summaries for the given environment.</p>
     */
    @GetMapping("/releases")
    public ResponseEntity<List<SnapshotSummary>> listSnapshots(
            @RequestParam(defaultValue = "production") String environment,
            @RequestParam(defaultValue = "50") int limit) {
        List<SnapshotSummary> snapshots = capabilityQueryUseCase.listSnapshots(environment, limit);
        return ResponseEntity.ok(snapshots);
    }
}
