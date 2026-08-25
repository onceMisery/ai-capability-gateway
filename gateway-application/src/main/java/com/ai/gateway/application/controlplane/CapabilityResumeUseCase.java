package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.service.LifecycleStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Restores a suspended capability to the validation stage.
 *
 * <p>Re-enabling is deliberately not a direct transition to {@code PUBLISHED}.
 * A suspended manifest must be validated again and then follow the normal
 * approval and publication workflow.</p>
 */
public final class CapabilityResumeUseCase {

    private static final Logger log = LoggerFactory.getLogger(CapabilityResumeUseCase.class);

    private final ManifestRepository manifestRepository;
    private final ManifestValidationUseCase manifestValidationUseCase;
    private final LifecycleStateMachine lifecycleStateMachine;

    public CapabilityResumeUseCase(ManifestRepository manifestRepository,
                                   ManifestValidationUseCase manifestValidationUseCase,
                                   LifecycleStateMachine lifecycleStateMachine) {
        this.manifestRepository = Objects.requireNonNull(manifestRepository);
        this.manifestValidationUseCase = Objects.requireNonNull(manifestValidationUseCase);
        this.lifecycleStateMachine = Objects.requireNonNull(lifecycleStateMachine);
    }

    public ResumeResult resume(String capabilityId, String version) {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(version, "version must not be null");

        ManifestRepository.ManifestDetail detail = manifestRepository.findAllWithDetails().stream()
                .filter(candidate -> candidate.manifest().metadata().id().equals(capabilityId)
                        && candidate.manifest().metadata().version().equals(version))
                .findFirst()
                .orElse(null);
        if (detail == null) {
            return new ResumeResult(Status.NOT_FOUND, "未找到指定的能力清单。");
        }
        if (detail.lifecycle() != CapabilityLifecycle.SUSPENDED) {
            return new ResumeResult(Status.REJECTED,
                    "只有处于「SUSPENDED」状态的能力才能恢复，当前状态为「"
                            + detail.lifecycle() + "」。");
        }
        boolean anotherActiveVersion = manifestRepository.findAllWithDetails().stream()
                .filter(candidate -> candidate.manifest().metadata().id().equals(capabilityId))
                .filter(candidate -> !candidate.manifest().metadata().version().equals(version))
                .anyMatch(candidate -> candidate.lifecycle() != CapabilityLifecycle.SUSPENDED
                        && candidate.lifecycle() != CapabilityLifecycle.RETIRED
                        && candidate.lifecycle() != CapabilityLifecycle.REJECTED);
        if (anotherActiveVersion) {
            return new ResumeResult(Status.REJECTED,
                    "能力「" + capabilityId
                            + "」已有其他活动版本，请先停用其他版本后再恢复当前版本。");
        }

        try {
            lifecycleStateMachine.validateTransition(
                    CapabilityLifecycle.SUSPENDED, CapabilityLifecycle.VALIDATED);
        } catch (IllegalArgumentException e) {
            log.warn("Cannot resume capability {}:{}: {}", capabilityId, version, e.getMessage());
            return new ResumeResult(Status.REJECTED, e.getMessage());
        }

        ManifestValidationUseCase.Result validation =
                manifestValidationUseCase.validate(capabilityId, version);
        if (validation.status() == ManifestValidationUseCase.Status.VALIDATED) {
            return new ResumeResult(Status.VALIDATED, null);
        }
        if (validation.status() == ManifestValidationUseCase.Status.INVALID) {
            return new ResumeResult(Status.INVALID, validation.error());
        }
        return new ResumeResult(Status.NOT_FOUND, validation.error());
    }

    public enum Status {
        VALIDATED,
        INVALID,
        NOT_FOUND,
        REJECTED
    }

    public record ResumeResult(Status status, String error) {
        public boolean success() {
            return status == Status.VALIDATED;
        }
    }
}
