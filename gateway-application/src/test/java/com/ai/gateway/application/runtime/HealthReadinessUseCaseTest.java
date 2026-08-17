package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.SecretManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthReadinessUseCaseTest {

    @Test
    void reportsMissingSecretsAsNotReady() {
        ManifestRepository manifests = mock(ManifestRepository.class);
        CatalogPort catalog = mock(CatalogPort.class);
        when(manifests.findAll()).thenReturn(List.of());
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(new CatalogSnapshot(1L, "production", List.of(), "policy", "digest"));
        SecretManager secrets = key -> { throw new IllegalStateException("missing"); };

        HealthReadinessUseCase.Result result =
                new HealthReadinessUseCase(manifests, catalog, secrets, "production").check();

        assertThat(result.ready()).isFalse();
        assertThat(result.checks()).containsEntry("requiredSecrets", "DOWN");
    }
}
