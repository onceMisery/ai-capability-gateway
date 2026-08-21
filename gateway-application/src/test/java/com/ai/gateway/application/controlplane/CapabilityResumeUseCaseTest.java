package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.service.LifecycleStateMachine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityResumeUseCaseTest {

    @Test
    void resumesSuspendedCapabilityThroughValidation() {
        ManifestRepository repository = mock(ManifestRepository.class);
        ManifestValidationUseCase validation = mock(ManifestValidationUseCase.class);
        CapabilityManifest manifest = mock(CapabilityManifest.class, RETURNS_DEEP_STUBS);
        when(manifest.metadata().id()).thenReturn("orders.query");
        when(manifest.metadata().version()).thenReturn("2.0.0");
        when(repository.findAllWithDetails()).thenReturn(List.of(
                new ManifestRepository.ManifestDetail(
                        manifest, CapabilityLifecycle.SUSPENDED, "digest", Instant.now())));
        when(validation.validate("orders.query", "2.0.0"))
                .thenReturn(new ManifestValidationUseCase.Result(
                        ManifestValidationUseCase.Status.VALIDATED, null, null));

        CapabilityResumeUseCase.ResumeResult result = new CapabilityResumeUseCase(
                repository, validation, new LifecycleStateMachine())
                .resume("orders.query", "2.0.0");

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(CapabilityResumeUseCase.Status.VALIDATED);
        verify(validation).validate("orders.query", "2.0.0");
    }

    @Test
    void rejectsResumeWhenCapabilityIsNotSuspended() {
        ManifestRepository repository = mock(ManifestRepository.class);
        ManifestValidationUseCase validation = mock(ManifestValidationUseCase.class);
        CapabilityManifest manifest = mock(CapabilityManifest.class, RETURNS_DEEP_STUBS);
        when(manifest.metadata().id()).thenReturn("orders.query");
        when(manifest.metadata().version()).thenReturn("2.0.0");
        when(repository.findAllWithDetails()).thenReturn(List.of(
                new ManifestRepository.ManifestDetail(
                        manifest, CapabilityLifecycle.PUBLISHED, "digest", Instant.now())));

        CapabilityResumeUseCase.ResumeResult result = new CapabilityResumeUseCase(
                repository, validation, new LifecycleStateMachine())
                .resume("orders.query", "2.0.0");

        assertThat(result.status()).isEqualTo(CapabilityResumeUseCase.Status.REJECTED);
    }
}
