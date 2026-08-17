package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.service.ManifestValidator;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManifestValidationUseCaseTest {

    @Test
    void validatesAndPersistsReportThroughApplicationOwner() {
        ManifestRepository repository = mock(ManifestRepository.class);
        ManifestValidator validator = mock(ManifestValidator.class);
        CapabilityManifest manifest = mock(CapabilityManifest.class);
        ValidationReport report = ValidationReport.success();
        when(repository.findByIdAndVersion("orders.query", "1.0.0"))
                .thenReturn(Optional.of(manifest));
        when(validator.validate(manifest)).thenReturn(report);

        ManifestValidationUseCase useCase = new ManifestValidationUseCase(repository, validator);

        ManifestValidationUseCase.Result result = useCase.validate("orders.query", "1.0.0");

        assertThat(result.status()).isEqualTo(ManifestValidationUseCase.Status.VALIDATED);
        assertThat(result.report()).isSameAs(report);
        verify(repository).recordValidation("orders.query", "1.0.0", report);
    }

    @Test
    void returnsNotFoundWithoutCallingValidator() {
        ManifestRepository repository = mock(ManifestRepository.class);
        ManifestValidator validator = mock(ManifestValidator.class);
        when(repository.findByIdAndVersion("missing", "1.0.0")).thenReturn(Optional.empty());

        ManifestValidationUseCase.Result result =
                new ManifestValidationUseCase(repository, validator).validate("missing", "1.0.0");

        assertThat(result.status()).isEqualTo(ManifestValidationUseCase.Status.NOT_FOUND);
    }
}
