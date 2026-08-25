package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ConfirmationSummary;
import com.ai.gateway.domain.model.ProjectionMapping;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.service.LifecycleStateMachine;
import com.ai.gateway.domain.service.ManifestDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Use case for the manifest approval flow defined in of the
 * design document.
 *
 * <p>After a Manifest passes all 10 automatic validation steps,
 * the system generates a confirmation summary. The submitter reviews the
 * summary and either confirms (approves) or rejects the manifest. This
 * simplified flow applies to READ_ONLY capabilities only. WRITE_LOW and
 * WRITE_HIGH require independent security review and dual approval
 *.</p>
 *
 * <p>The confirmation record must bind to the Manifest digest, confirmer,
 * time, environment, and opinion (confirm or reject). When Manifest content
 * changes, old confirmations are automatically invalidated.</p>
 *
 * <p>Batch approval is supported for the first 5-10 read-only capabilities,
 * allowing a streamlined onboarding process.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @see LifecycleStateMachine
 * @see ManifestRepository
 * @since 0.1.0
 */
public final class ManifestApprovalUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManifestApprovalUseCase.class);

    /**
     * The maximum number of manifests that can be batch-approved in a single
     * operation.
     */
    private static final int MAX_BATCH_SIZE = 10;

    private final ManifestRepository manifestRepository;
    private final LifecycleStateMachine lifecycleStateMachine;

    /**
     * Constructs a new ManifestApprovalUseCase with the required dependencies.
     *
     * @param manifestRepository the repository for retrieving and updating manifests
     * @param lifecycleStateMachine the state machine for validating lifecycle transitions
     * @throws NullPointerException if any argument is null
     */
    public ManifestApprovalUseCase(ManifestRepository manifestRepository,
                                    LifecycleStateMachine lifecycleStateMachine) {
        this.manifestRepository = java.util.Objects.requireNonNull(
                manifestRepository, "manifestRepository must not be null");
        this.lifecycleStateMachine = java.util.Objects.requireNonNull(
                lifecycleStateMachine, "lifecycleStateMachine must not be null");
    }

    /**
     * Approves a manifest that is in the VALIDATED state.
     *
     * <p>The method:</p>
     * <ol>
     * <li>Retrieves the manifest by ID and version.</li>
     * <li>Validates that the current lifecycle state is VALIDATED.</li>
     * <li>Generates a ConfirmationSummary bound to the manifest content.</li>
     * <li>Transitions the lifecycle to APPROVED via the state machine.</li>
     * </ol>
     *
     * @param id the capability identifier
     * @param version the semantic version
     * @param approver the approver identity (for audit)
     * @return the approval result
     * @throws NullPointerException if any argument is null
     */
    public ApprovalResult approve(String id, String version, String approver) {
        java.util.Objects.requireNonNull(id, "id must not be null");
        java.util.Objects.requireNonNull(version, "version must not be null");
        java.util.Objects.requireNonNull(approver, "approver must not be null");
        log.info("Approving manifest: id={}, version={}, approver={}", id, version, approver);

        Optional<CapabilityManifest> manifestOpt = manifestRepository.findByIdAndVersion(id, version);
        if (manifestOpt.isEmpty()) {
            return new ApprovalResult(false, null,
                    "未找到能力清单：能力「" + id + "」，版本「" + version + "」。");
        }

        CapabilityManifest manifest = manifestOpt.get();

        CapabilityLifecycle currentLifecycle = currentLifecycle(id, version);
        if (currentLifecycle != CapabilityLifecycle.VALIDATED) {
            return new ApprovalResult(false, null,
                    "Cannot approve manifest in lifecycle: " + currentLifecycle);
        }

        // Validate the lifecycle transition: VALIDATED -> APPROVED
        try {
            lifecycleStateMachine.validateTransition(
                    currentLifecycle, CapabilityLifecycle.APPROVED);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid lifecycle transition for id={}, version={}: {}",
                    id, version, e.getMessage());
            return new ApprovalResult(false, null,
                    "Cannot approve: " + e.getMessage());
        }

        // Generate the confirmation summary
        ConfirmationSummary summary = generateConfirmationSummary(manifest);

        // Transition to APPROVED
        manifestRepository.recordApproval(id, version, approver, "APPROVED", summary);
        log.info("Manifest approved: id={}, version={}, approver={}", id, version, approver);

        return new ApprovalResult(true, summary, null);
    }

    /**
     * Rejects a manifest that is in the VALIDATED state
     *
     * <p>The manifest transitions to terminal REJECTED. Corrections require
     * importing a new manifest version.</p>
     *
     * @param id the capability identifier
     * @param version the semantic version
     * @param approver the rejecter identity (for audit)
     * @param reason the rejection reason
     * @return the approval result
     * @throws NullPointerException if any argument is null
     */
    public ApprovalResult reject(String id, String version, String approver, String reason) {
        java.util.Objects.requireNonNull(id, "id must not be null");
        java.util.Objects.requireNonNull(version, "version must not be null");
        java.util.Objects.requireNonNull(approver, "approver must not be null");
        java.util.Objects.requireNonNull(reason, "reason must not be null");
        log.info("Rejecting manifest: id={}, version={}, approver={}, reason={}",
                id, version, approver, reason);

        Optional<CapabilityManifest> manifestOpt = manifestRepository.findByIdAndVersion(id, version);
        if (manifestOpt.isEmpty()) {
            return new ApprovalResult(false, null,
                    "未找到能力清单：能力「" + id + "」，版本「" + version + "」。");
        }

        // Enforce the state machine: only VALIDATED may transition to
        // terminal REJECTED.
        CapabilityLifecycle currentLifecycle = currentLifecycle(id, version);
        if (currentLifecycle == null) {
            return new ApprovalResult(false, null,
                    "Cannot determine lifecycle state of manifest: id=" + id + ", version=" + version);
        }
        try {
            lifecycleStateMachine.validateTransition(currentLifecycle, CapabilityLifecycle.REJECTED);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid rejection transition for id={}, version={}: {}",
                    id, version, e.getMessage());
            return new ApprovalResult(false, null,
                    "Cannot reject: " + e.getMessage());
        }

        // Transition to terminal REJECTED.
        manifestRepository.recordApproval(id, version, approver, "REJECTED", null);
        log.info("Manifest rejected: id={}, version={}", id, version);

        return new ApprovalResult(true, null, null);
    }

    /**
     * Resolves the current persisted lifecycle state of a manifest.
     *
     * @param id the capability identifier
     * @param version the semantic version
     * @return the current lifecycle, or {@code null} if the manifest is unknown
     */
    private CapabilityLifecycle currentLifecycle(String id, String version) {
        return manifestRepository.findAllWithDetails().stream()
                .filter(detail -> detail.manifest().metadata().id().equals(id)
                        && detail.manifest().metadata().version().equals(version))
                .map(ManifestRepository.ManifestDetail::lifecycle)
                .findFirst()
                .orElse(null);
    }

    /**
     * Batch-approves the first 5-10 read-only capabilities.
     *
     * <p>This streamlined flow is available only for READ_ONLY capabilities.
     * WRITE_LOW and WRITE_HIGH require independent security review and dual
     * approval. The batch is limited to {@value #MAX_BATCH_SIZE} manifests.</p>
     *
     * @param manifestRefs the list of manifest references in "id:version" format
     * @param approver the approver identity
     * @return the batch approval result
     * @throws NullPointerException if any argument is null
     */
    public BatchApprovalResult batchApprove(List<String> manifestRefs, String approver) {
        java.util.Objects.requireNonNull(manifestRefs, "manifestRefs must not be null");
        java.util.Objects.requireNonNull(approver, "approver must not be null");
        log.info("Batch approving {} manifests, approver={}", manifestRefs.size(), approver);

        List<String> approved = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        int count = 0;
        for (String ref : manifestRefs) {
            if (count >= MAX_BATCH_SIZE) {
                log.warn("Batch approval limit ({}) reached; {} manifests skipped",
                        MAX_BATCH_SIZE, manifestRefs.size() - count);
                break;
            }

            String[] parts = ref.split(":");
            if (parts.length != 2) {
                failed.add(ref + " (invalid format, expected id:version)");
                continue;
            }

            String id = parts[0];
            String version = parts[1];

            Optional<CapabilityManifest> manifestOpt = manifestRepository.findByIdAndVersion(id, version);
            if (manifestOpt.isEmpty()) {
                failed.add(ref + " (not found)");
                continue;
            }

            CapabilityManifest manifest = manifestOpt.get();

            // Only READ_ONLY capabilities may be batch-approved
            if (manifest.spec().risk() != RiskLevel.READ_ONLY) {
                failed.add(ref + " (not READ_ONLY, requires individual review)");
                continue;
            }

            ApprovalResult result = approve(id, version, approver);
            if (result.success()) {
                approved.add(ref);
            } else {
                failed.add(ref + " (" + result.error() + ")");
            }
            count++;
        }

        log.info("Batch approval complete: {} approved, {} failed", approved.size(), failed.size());
        return new BatchApprovalResult(approved, failed);
    }

    /**
     * Generates a confirmation summary for the given manifest.
     *
     * <p>The summary contains at minimum: capability ID, version, risk level,
     * protocol binding summary, model-visible fields, principal-injected fields,
     * output projections, redaction rules, required permissions, and the
     * manifest content SHA-256 digest.</p>
     *
     * @param manifest the capability manifest
     * @return the confirmation summary
     */
    private ConfirmationSummary generateConfirmationSummary(CapabilityManifest manifest) {
        CapabilityManifest.Spec spec = manifest.spec();
        CapabilityManifest.Metadata meta = manifest.metadata();

        // Extract model-visible and principal-injected field names
        List<String> modelVisibleFields = new ArrayList<>();
        List<String> principalInjectedFields = new ArrayList<>();

        spec.invocation().arguments().forEach(arg -> {
            if (arg.isComposite()) {
                arg.objectBindings().forEach((path, fb) -> {
                    if (fb.source() == com.ai.gateway.domain.model.ArgumentSource.MODEL) {
                        modelVisibleFields.add(path);
                    } else if (fb.source() == com.ai.gateway.domain.model.ArgumentSource.PRINCIPAL) {
                        principalInjectedFields.add(path);
                    }
                });
            } else {
                if (arg.source() == com.ai.gateway.domain.model.ArgumentSource.MODEL) {
                    modelVisibleFields.add(arg.sourcePath());
                } else if (arg.source() == com.ai.gateway.domain.model.ArgumentSource.PRINCIPAL) {
                    principalInjectedFields.add(arg.sourcePath());
                }
            }
        });

        // Extract output projections and redactions
        List<ProjectionMapping> projections = spec.output().projections();
        List<com.ai.gateway.domain.model.RedactionRule> redactions = spec.output().redactions();

        // Extract required permissions
        List<String> requiredPermissions = spec.authorization() != null
                ? spec.authorization().permissions()
                : List.of();

        // Generate manifest digest
        String digest = generateManifestDigest(manifest);

        return new ConfirmationSummary(
                meta.id(),
                meta.version(),
                spec.risk(),
                spec.invocation().interfaceName(),
                spec.invocation().method(),
                spec.invocation().serialization(),
                modelVisibleFields,
                principalInjectedFields,
                projections,
                redactions,
                requiredPermissions,
                "PENDING",
                digest
        );
    }

    /**
     * Generates a SHA-256 digest of the manifest content for the confirmation
     * summary.
     *
     * @param manifest the manifest
     * @return the hex-encoded digest
     */
    private String generateManifestDigest(CapabilityManifest manifest) {
        return ManifestDigest.sha256(manifest);
    }

    /**
     * The result of an approval or rejection operation.
     *
     * @param success whether the operation succeeded
     * @param summary the confirmation summary for approval; null for rejection or failure
     * @param error the error message; null on success
     */
    public record ApprovalResult(
            boolean success,
            ConfirmationSummary summary,
            String error
    ) {
    }

    /**
     * The result of a batch approval operation.
     *
     * @param approved the list of successfully approved manifest references
     * @param failed the list of failed manifest references with reasons
     */
    public record BatchApprovalResult(
            List<String> approved,
            List<String> failed
    ) {
    }
}
