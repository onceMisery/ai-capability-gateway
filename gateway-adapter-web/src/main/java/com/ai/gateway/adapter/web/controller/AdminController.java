package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.manifest.ManifestDocumentMapper;
import com.ai.gateway.application.controlplane.CapabilitySuspendUseCase;
import com.ai.gateway.application.controlplane.CatalogPublishUseCase;
import com.ai.gateway.application.controlplane.CatalogRollbackUseCase;
import com.ai.gateway.application.controlplane.ManifestApprovalUseCase;
import com.ai.gateway.application.controlplane.ManifestImportUseCase;
import com.ai.gateway.application.controlplane.ManifestValidationUseCase;
import com.ai.gateway.application.controlplane.CatalogSnapshotQueryUseCase;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.ManifestDocumentValidator;
import com.ai.gateway.domain.service.Sha256Digest;
import com.ai.gateway.adapter.web.support.SecurityHelper;
import com.ai.gateway.domain.model.AdminAction;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * REST controller for the management/admin API
 *
 * <p>This controller exposes control-plane endpoints for manifest lifecycle
 * management, catalog publication, rollback, capability suspension, and audit
 * querying:</p>
 * <ul>
 * <li>{@code POST /admin/v1/manifests:import} — 通过 10 步校验流水线导入清单。</li>
 * <li>{@code POST /admin/v1/capabilities/{id}/versions/{version}:validate}
 * — 重新校验既有清单版本。</li>
 * <li>{@code POST /admin/v1/capabilities/{id}/versions/{version}:approve}
 * — 审批已通过校验的清单。</li>
 * <li>{@code POST /admin/v1/releases:publish} — 发布新快照。</li>
 * <li>{@code POST /admin/v1/releases:rollback} — 回滚至历史快照。</li>
 * <li>{@code POST /admin/v1/capabilities/{id}:suspend} — 紧急下线能力。</li>
 * <li>{@code GET /admin/v1/releases/{snapshotVersion}} — 按版本获取快照。</li>
 * <li>{@code GET /admin/v1/audits} — 查询审计事件。</li>
 * </ul>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final ManifestImportUseCase manifestImportUseCase;
    private final ManifestApprovalUseCase manifestApprovalUseCase;
    private final CatalogPublishUseCase catalogPublishUseCase;
    private final CatalogRollbackUseCase catalogRollbackUseCase;
    private final CapabilitySuspendUseCase capabilitySuspendUseCase;
    private final ManifestValidationUseCase manifestValidationUseCase;
    private final CatalogSnapshotQueryUseCase catalogSnapshotQueryUseCase;
    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final ManifestDocumentValidator manifestDocumentValidator;
    private final ManifestDocumentMapper manifestDocumentMapper;

    /**
     * 通过 10 步校验流水线导入能力清单。
     *
     * @param document 原始能力清单文档
     * @return 含校验报告与清单摘要的导入结果
     */
    @PostMapping("/manifests:import")
    public ResponseEntity<Map<String, Object>> importManifest(
            @RequestBody JsonNode document) {

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.IMPORT);

        ValidationReport documentReport = manifestDocumentValidator.validate(
                manifestDocumentMapper.toValidationTree(document));
        if (!documentReport.valid()) {
            return rejectedManifestDocument(documentReport, "Manifest Schema 校验失败");
        }

        CapabilityManifest manifest;
        try {
            manifest = manifestDocumentMapper.toDomain(document);
        } catch (IllegalArgumentException e) {
            log.warn("Manifest document mapping failed: {}", e.getMessage());
            return rejectedManifestDocument(
                    ValidationReport.failure(List.of(e.getMessage())),
                    "Manifest 字段映射失败");
        }

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

    private ResponseEntity<Map<String, Object>> rejectedManifestDocument(
            ValidationReport report,
            String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "REJECTED");
        body.put("error", error);
        body.put("validationReport", buildValidationReportBody(report));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 重新校验既有清单版本。
     *
     * @param id 能力标识
     * @param version 语义化版本号
     * @return 校验结果
     */
    @PostMapping("/capabilities/{id}/versions/{version}:validate")
    public ResponseEntity<Map<String, Object>> validate(
            @PathVariable String id,
            @PathVariable String version) {

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.APPROVE);

        log.info("Manifest validation requested: id={}, version={}", id, version);

        ManifestValidationUseCase.Result result = manifestValidationUseCase.validate(id, version);
        if (result.status() == ManifestValidationUseCase.Status.NOT_FOUND) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "NOT_FOUND");
            body.put("message", "Manifest not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.status().name());
        body.put("validationReport", buildValidationReportBody(result.report()));
        return ResponseEntity.ok(body);
    }

    /**
     * 审批已通过校验的清单。
     *
     * @param id 能力标识
     * @param version 语义化版本号
     * @param request 含审批人身份的审批请求
     * @return 含确认摘要的审批结果
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
     * 向指定环境发布新的目录快照。
     *
     * @param request 含目标环境的发布请求
     * @return 含新快照版本的发布结果
     */
    @PostMapping("/releases:publish")
    public ResponseEntity<Map<String, Object>> publish(
            @RequestBody @Valid PublishRequest request) {

        SecurityHelper.requireAdmin(authenticationPort, authorizationPort, AdminAction.PUBLISH);

        String environment = request.environment() != null ? request.environment() : "production";
        log.info("Catalog publish requested: environment={}", environment);

        List<CatalogPublishUseCase.SelectedCapability> selected =
                request.capabilities() == null ? List.of() : request.capabilities().stream()
                        .map(c -> new CatalogPublishUseCase.SelectedCapability(
                                c.capabilityId(), c.version()))
                        .toList();

        CatalogPublishUseCase.PublishResult result =
                catalogPublishUseCase.publish(environment, selected);

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
     * 将目录回滚至历史快照版本。
     *
     * @param request 含目标版本与环境信息的回滚请求
     * @return 含新快照版本的回滚结果
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
     * 立即下线指定能力。
     *
     * @param id 待下线能力标识
     * @param request 含下线原因与操作人的请求
     * @return 下线结果
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
     * 按版本获取目录快照。
     *
     * @param snapshotVersion 快照版本号
     * @return 目录快照
     */
    @GetMapping("/releases/{snapshotVersion}")
    public ResponseEntity<Map<String, Object>> getSnapshot(
            @PathVariable long snapshotVersion) {

        log.debug("Snapshot query: version={}", snapshotVersion);

        CatalogSnapshot snapshot = catalogSnapshotQueryUseCase.find(snapshotVersion).orElse(null);

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

        // 仅列出能力 ID 与版本，不暴露完整清单细节
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
     * 构建校验报告的响应体。
     *
     * @param report 校验报告
     * @return 响应体 Map
     */
    private Map<String, Object> buildValidationReportBody(ValidationReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", report.valid());
        body.put("errors", report.errors());
        body.put("warnings", report.warnings());
        return body;
    }

    /**
     * 对外暴露前清理错误消息中的内部信息。
     *
     * @param message 原始错误消息
     * @return 可安全对外暴露的清理后消息
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
        return Sha256Digest.sha256Hex(subject);
    }

    /**
     * POST /capabilities/{id}/versions/{version}:approve 的请求体。
     *
     * @param approver 审批人身份
     */
    public record ApproveRequest(
            @Size(max = 256)
            String approver
    ) {
    }

    /**
     * POST /releases:publish 的请求体。
     *
     * @param environment 目标环境
     * @param capabilities 待发布的能力列表；若为空则发布全部 APPROVED 能力
     */
    public record PublishRequest(
            @Size(max = 64)
            String environment,
            @Valid
            List<SelectedCapabilityRequest> capabilities
    ) {
    }

    /**
     * 发布请求中选定的待发布能力。
     *
     * @param capabilityId 唯一能力标识
     * @param version 待发布版本
     */
    public record SelectedCapabilityRequest(
            @NotBlank
            @Size(max = 128)
            String capabilityId,
            @NotBlank
            @Size(max = 64)
            String version
    ) {
    }

    /**
     * POST /releases:rollback 的请求体。
     *
     * @param targetSnapshotVersion 回滚目标历史快照版本
     * @param environment 目标环境
     */
    public record RollbackRequest(
            @Min(1)
            long targetSnapshotVersion,
            @Size(max = 64)
            String environment
    ) {
    }

    /**
     * POST /capabilities/{id}:suspend 的请求体。
     *
     * @param reason 下线原因
     * @param operator 执行下线的操作人
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
