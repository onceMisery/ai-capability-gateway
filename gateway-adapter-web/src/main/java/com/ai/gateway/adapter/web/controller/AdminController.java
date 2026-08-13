package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.controlplane.CapabilitySuspendUseCase;
import com.ai.gateway.application.controlplane.CatalogPublishUseCase;
import com.ai.gateway.application.controlplane.CatalogRollbackUseCase;
import com.ai.gateway.application.controlplane.ManifestApprovalUseCase;
import com.ai.gateway.application.controlplane.ManifestImportUseCase;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.service.ManifestValidator;
import com.ai.gateway.adapter.web.support.SecurityHelper;
import com.ai.gateway.domain.model.AdminAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST controller for the management/admin API
 *
 * <p>This controller exposes control-plane endpoints for manifest lifecycle
 * management, catalog publication, rollback, capability suspension, and audit
 * querying:</p>
 * <ul>
 * <li>{@code POST /admin/v1/manifests:import} — import a manifest through
 * the 10-step validation pipeline .</li>
 * <li>{@code POST /admin/v1/capabilities/{id}/versions/{version}:validate}
 * — re-validate an existing manifest version .</li>
 * <li>{@code POST /admin/v1/capabilities/{id}/versions/{version}:approve}
 * — approve a validated manifest .</li>
 * <li>{@code POST /admin/v1/releases:publish} — publish a new snapshot
 * .</li>
 * <li>{@code POST /admin/v1/releases:rollback} — rollback to a historical
 * snapshot .</li>
 * <li>{@code POST /admin/v1/capabilities/{id}:suspend} — emergency suspend
 * a capability .</li>
 * <li>{@code GET /admin/v1/releases/{snapshotVersion}} — retrieve a
 * snapshot by version.</li>
 * <li>{@code GET /admin/v1/audits} — query audit events .</li>
 * </ul>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/admin/v1")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ManifestImportUseCase manifestImportUseCase;
    private final ManifestApprovalUseCase manifestApprovalUseCase;
    private final CatalogPublishUseCase catalogPublishUseCase;
    private final CatalogRollbackUseCase catalogRollbackUseCase;
    private final CapabilitySuspendUseCase capabilitySuspendUseCase;
    private final ManifestRepository manifestRepository;
    private final ManifestValidator manifestValidator;
    private final CatalogPort catalogPort;
    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;

    /**
     * Constructs a new AdminController.
     *
     * @param manifestImportUseCase the manifest import use case
     * @param manifestApprovalUseCase the manifest approval use case
     * @param catalogPublishUseCase the catalog publish use case
     * @param catalogRollbackUseCase the catalog rollback use case
     * @param capabilitySuspendUseCase the capability suspend use case
     * @param manifestRepository the manifest repository for loading manifests
     * @param manifestValidator the manifest validator for re-validation
     * @param catalogPort the catalog port for snapshot queries
     * @throws NullPointerException if any argument is null
     */
    public AdminController(ManifestImportUseCase manifestImportUseCase,
                           ManifestApprovalUseCase manifestApprovalUseCase,
                           CatalogPublishUseCase catalogPublishUseCase,
                           CatalogRollbackUseCase catalogRollbackUseCase,
                           CapabilitySuspendUseCase capabilitySuspendUseCase,
                           ManifestRepository manifestRepository,
                           ManifestValidator manifestValidator,
                           CatalogPort catalogPort,
                           AuthenticationPort authenticationPort,
                           AuthorizationPort authorizationPort) {
        this.manifestImportUseCase = Objects.requireNonNull(manifestImportUseCase,
                "manifestImportUseCase must not be null");
        this.manifestApprovalUseCase = Objects.requireNonNull(manifestApprovalUseCase,
                "manifestApprovalUseCase must not be null");
        this.catalogPublishUseCase = Objects.requireNonNull(catalogPublishUseCase,
                "catalogPublishUseCase must not be null");
        this.catalogRollbackUseCase = Objects.requireNonNull(catalogRollbackUseCase,
                "catalogRollbackUseCase must not be null");
        this.capabilitySuspendUseCase = Objects.requireNonNull(capabilitySuspendUseCase,
                "capabilitySuspendUseCase must not be null");
        this.manifestRepository = Objects.requireNonNull(manifestRepository,
                "manifestRepository must not be null");
        this.manifestValidator = Objects.requireNonNull(manifestValidator,
                "manifestValidator must not be null");
        this.catalogPort = Objects.requireNonNull(catalogPort,
                "catalogPort must not be null");
        this.authenticationPort = Objects.requireNonNull(authenticationPort,
                "authenticationPort must not be null");
        this.authorizationPort = Objects.requireNonNull(authorizationPort,
                "authorizationPort must not be null");
    }

    /**
     * Imports a Capability Manifest through the 10-step validation pipeline
     *
     * @param manifest the capability manifest to import
     * @return the import result with validation report and manifest digest
     */
    @PostMapping("/manifests:import")
    public ResponseEntity<Map<String, Object>> importManifest(
            @RequestBody @Valid CapabilityManifest manifest) {

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.IMPORT);

        log.info("Manifest import requested: id={}, version={}",
                manifest.metadata().id(), manifest.metadata().version());

        ManifestImportUseCase.ImportResult result =
                manifestImportUseCase.importManifest(manifest);

        Map<String, Object> body = new LinkedHashMap<>();

        if (result.success()) {
            body.put("status", "IMPORTED");
            body.put("manifestDigest", result.manifestDigest());
            body.put("validationReport", buildValidationReportBody(result.report()));
            return ResponseEntity.ok(body);
        } else {
            body.put("status", "REJECTED");
            body.put("error", sanitizeErrorMessage(result.error()));
            body.put("validationReport", buildValidationReportBody(result.report()));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
    }

    /**
     * Re-validates an existing manifest version
     *
     * @param id the capability identifier
     * @param version the semantic version
     * @return the validation result
     */
    @PostMapping("/capabilities/{id}/versions/{version}:validate")
    public ResponseEntity<Map<String, Object>> validate(
            @PathVariable String id,
            @PathVariable String version) {

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.APPROVE);

        log.info("Manifest validation requested: id={}, version={}", id, version);

        var manifestOpt = manifestRepository.findByIdAndVersion(id, version);
        if (manifestOpt.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "NOT_FOUND");
            body.put("message", "Manifest not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        ValidationReport report = manifestValidator.validate(manifestOpt.get());
        manifestRepository.recordValidation(id, version, report);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", report.valid() ? "VALIDATED" : "INVALID");
        body.put("validationReport", buildValidationReportBody(report));
        return ResponseEntity.ok(body);
    }

    /**
     * Approves a validated manifest
     *
     * @param id the capability identifier
     * @param version the semantic version
     * @param request the approval request containing the approver identity
     * @return the approval result with confirmation summary
     */
    @PostMapping("/capabilities/{id}/versions/{version}:approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable String id,
            @PathVariable String version,
            @RequestBody @Valid ApproveRequest request) {

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.APPROVE);

        var principal = SecurityHelper.getCurrentPrincipal(authenticationPort);
        if (principal == null) throw new SecurityException("AUTHENTICATION_FAILED: no valid principal");
        String approver = subjectDigest(principal.subject());
        log.info("Manifest approval requested: id={}, version={}, approverDigest={}",
                id, version, approver);

        ManifestApprovalUseCase.ApprovalResult result =
                manifestApprovalUseCase.approve(id, version, approver);

        Map<String, Object> body = new LinkedHashMap<>();

        if (result.success()) {
            body.put("status", "APPROVED");
            if (result.summary() != null) {
                body.put("capabilityId", result.summary().capabilityId());
                body.put("capabilityVersion", result.summary().version());
                body.put("riskLevel", result.summary().risk() != null
                        ? result.summary().risk().name() : null);
            }
            return ResponseEntity.ok(body);
        } else {
            body.put("status", "REJECTED");
            body.put("message", sanitizeErrorMessage(result.error()));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
    }

    /**
     * Publishes a new catalog snapshot to the specified environment
     *
     * @param request the publish request containing the target environment
     * @return the publish result with the new snapshot version
     */
    @PostMapping("/releases:publish")
    public ResponseEntity<Map<String, Object>> publish(
            @RequestBody @Valid PublishRequest request) {

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.PUBLISH);

        String environment = request.environment() != null ? request.environment() : "production";
        log.info("Catalog publish requested: environment={}", environment);

        CatalogPublishUseCase.PublishResult result =
                catalogPublishUseCase.publish(environment);

        Map<String, Object> body = new LinkedHashMap<>();

        if (result.success()) {
            body.put("status", "PUBLISHED");
            body.put("snapshotVersion", result.snapshotVersion());
            return ResponseEntity.ok(body);
        } else {
            body.put("status", "FAILED");
            body.put("message", sanitizeErrorMessage(result.error()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    /**
     * Rolls back the catalog to a historical snapshot version
     *
     * @param request the rollback request containing the target version and environment
     * @return the rollback result with the new snapshot version
     */
    @PostMapping("/releases:rollback")
    public ResponseEntity<Map<String, Object>> rollback(
            @RequestBody @Valid RollbackRequest request) {

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.ROLLBACK);

        String environment = request.environment() != null ? request.environment() : "production";
        log.info("Catalog rollback requested: targetVersion={}, environment={}",
                request.targetSnapshotVersion(), environment);

        CatalogRollbackUseCase.RollbackResult result =
                catalogRollbackUseCase.rollback(request.targetSnapshotVersion(), environment);

        Map<String, Object> body = new LinkedHashMap<>();

        if (result.success()) {
            body.put("status", "ROLLED_BACK");
            body.put("newSnapshotVersion", result.snapshotVersion());
            return ResponseEntity.ok(body);
        } else {
            body.put("status", "FAILED");
            body.put("message", sanitizeErrorMessage(result.error()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    /**
     * Suspends a capability immediately
     *
     * @param id the capability identifier to suspend
     * @param request the suspend request containing the reason and operator
     * @return the suspension result
     */
    @PostMapping("/capabilities/{id}:suspend")
    public ResponseEntity<Map<String, Object>> suspend(
            @PathVariable String id,
            @RequestBody @Valid SuspendRequest request) {

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.SUSPEND);

        var principal = SecurityHelper.getCurrentPrincipal(authenticationPort);
        if (principal == null) throw new SecurityException("AUTHENTICATION_FAILED: no valid principal");
        String operator = subjectDigest(principal.subject());
        log.warn("Capability suspension requested: id={}, reason={}, operatorDigest={}",
                id, request.reason(), operator);

        CapabilitySuspendUseCase.SuspendResult result =
                capabilitySuspendUseCase.suspend(id, request.reason(), operator);

        Map<String, Object> body = new LinkedHashMap<>();

        if (result.success()) {
            body.put("status", "SUSPENDED");
            body.put("capabilityId", id);
            body.put("newSnapshotVersion", result.snapshotVersion());
            return ResponseEntity.ok(body);
        } else {
            body.put("status", "FAILED");
            body.put("message", sanitizeErrorMessage(result.error()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    /**
     * Retrieves a catalog snapshot by version
     *
     * @param snapshotVersion the snapshot version number
     * @return the catalog snapshot
     */
    @GetMapping("/releases/{snapshotVersion}")
    public ResponseEntity<Map<String, Object>> getSnapshot(
            @PathVariable long snapshotVersion) {

        log.debug("Snapshot query: version={}", snapshotVersion);

        CatalogSnapshot snapshot = catalogPort.loadSnapshot(snapshotVersion);

        Map<String, Object> body = new LinkedHashMap<>();

        if (snapshot == null) {
            body.put("status", "NOT_FOUND");
            body.put("message", "Snapshot version not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        body.put("snapshotVersion", snapshot.snapshotVersion());
        body.put("environment", snapshot.environment());
        body.put("policyRef", snapshot.policyRef());
        body.put("digest", snapshot.digest());
        body.put("capabilityCount", snapshot.capabilities().size());

        // List capability IDs and versions without exposing full manifest details
        List<Map<String, String>> capabilities = snapshot.capabilities().stream()
                .map(m -> Map.of(
                        "id", m.metadata().id(),
                        "version", m.metadata().version()
                ))
                .toList();
        body.put("capabilities", capabilities);

        return ResponseEntity.ok(body);
    }

    /**
     * Builds a validation report response body
     *
     * @param report the validation report
     * @return the response body map
     */
    private Map<String, Object> buildValidationReportBody(ValidationReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", report.valid());
        body.put("errors", report.errors());
        body.put("warnings", report.warnings());
        return body;
    }

    /**
     * Sanitizes an error message for external exposure
     *
     * @param message the raw error message
     * @return the sanitized message safe for external exposure
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return "An internal error occurred";
        }
        String sanitized = message.replaceAll("at\\s+\\S+\\.\\S+\\([^)]*\\)", "[internal]");
        sanitized = sanitized.replaceAll("/\\S+\\.java:\\d+", "[internal]");
        return sanitized;
    }

    private String subjectDigest(String subject) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new InternalError("SHA-256 is unavailable", e);
        }
    }

    /**
     * Request body for POST /capabilities/{id}/versions/{version}:approve
     *
     * @param approver the approver identity
     */
    public record ApproveRequest(
            @Size(max = 256)
            String approver
    ) {
    }

    /**
     * Request body for POST /releases:publish
     *
     * @param environment the target environment
     */
    public record PublishRequest(
            @Size(max = 64)
            String environment
    ) {
    }

    /**
     * Request body for POST /releases:rollback
     *
     * @param targetSnapshotVersion the historical snapshot version to roll back to
     * @param environment the target environment
     */
    public record RollbackRequest(
            @Min(1)
            long targetSnapshotVersion,
            @Size(max = 64)
            String environment
    ) {
    }

    /**
     * Request body for POST /capabilities/{id}:suspend
     *
     * @param reason the suspension reason
     * @param operator the operator performing the suspension
     */
    public record SuspendRequest(
            @NotBlank
            @Size(max = 512)
            String reason,
            @Size(max = 256)
            String operator
    ) {
    }
}
