package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.CompatibilityTestPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.service.ManifestValidator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManifestImportUseCaseTest {

    @Test
    void rejectsAnotherVersionWhileCapabilityIsActive() {
        ManifestRepository repository = mock(ManifestRepository.class);
        ManifestValidator validator = mock(ManifestValidator.class);
        CapabilityManifest existing = mock(CapabilityManifest.class, RETURNS_DEEP_STUBS);
        CapabilityManifest incoming = mock(CapabilityManifest.class, RETURNS_DEEP_STUBS);
        when(existing.metadata().id()).thenReturn("orders.query");
        when(existing.metadata().version()).thenReturn("1.0.0");
        when(incoming.metadata().id()).thenReturn("orders.query");
        when(incoming.metadata().version()).thenReturn("2.0.0");
        when(repository.findByIdAndVersion("orders.query", "2.0.0"))
                .thenReturn(Optional.empty());
        when(repository.findAllWithDetails()).thenReturn(List.of(
                new ManifestRepository.ManifestDetail(
                        existing, CapabilityLifecycle.PUBLISHED, "digest", Instant.now())));

        ManifestImportUseCase.ImportResult result = new ManifestImportUseCase(
                repository, validator, mock(SchemaValidator.class),
                mock(CompatibilityTestPort.class)).importManifest(incoming);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("suspend it before importing another version");
        verifyNoInteractions(validator);
    }

    @Test
    void permitsNewVersionAfterExistingCapabilityIsSuspended() {
        ManifestRepository repository = mock(ManifestRepository.class);
        ManifestValidator validator = mock(ManifestValidator.class);
        CapabilityManifest existing = mock(CapabilityManifest.class, RETURNS_DEEP_STUBS);
        CapabilityManifest incoming = mock(CapabilityManifest.class, RETURNS_DEEP_STUBS);
        when(existing.metadata().id()).thenReturn("orders.query");
        when(existing.metadata().version()).thenReturn("1.0.0");
        when(incoming.metadata().id()).thenReturn("orders.query");
        when(incoming.metadata().version()).thenReturn("2.0.0");
        when(repository.findByIdAndVersion("orders.query", "2.0.0"))
                .thenReturn(Optional.empty());
        when(repository.findAllWithDetails()).thenReturn(List.of(
                new ManifestRepository.ManifestDetail(
                        existing, CapabilityLifecycle.SUSPENDED, "digest", Instant.now())));
        when(validator.validate(incoming))
                .thenReturn(ValidationReport.failure(List.of("test validation failure")));

        ManifestImportUseCase.ImportResult result = new ManifestImportUseCase(
                repository, validator, mock(SchemaValidator.class),
                mock(CompatibilityTestPort.class)).importManifest(incoming);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Validation failed");
    }
}
