package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.service.ManifestValidator;

import java.util.Objects;

/** Owns manifest re-validation and persistence of its validation report. */
public final class ManifestValidationUseCase {

    private final ManifestRepository manifestRepository;
    private final ManifestValidator manifestValidator;

    public ManifestValidationUseCase(ManifestRepository manifestRepository,
                                     ManifestValidator manifestValidator) {
        this.manifestRepository = Objects.requireNonNull(manifestRepository);
        this.manifestValidator = Objects.requireNonNull(manifestValidator);
    }

    public Result validate(String capabilityId, String version) {
        Objects.requireNonNull(capabilityId);
        Objects.requireNonNull(version);
        CapabilityManifest manifest = manifestRepository.findByIdAndVersion(capabilityId, version)
                .orElse(null);
        if (manifest == null) {
            return new Result(Status.NOT_FOUND, null, "Manifest not found");
        }
        ValidationReport report = manifestValidator.validate(manifest);
        manifestRepository.recordValidation(capabilityId, version, report);
        return new Result(report.valid() ? Status.VALIDATED : Status.INVALID, report,
                report.valid() ? null : "Manifest validation failed");
    }

    public enum Status { VALIDATED, INVALID, NOT_FOUND }

    public record Result(Status status, ValidationReport report, String error) {
    }
}
