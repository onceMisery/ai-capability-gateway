package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.SecretManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void missingRequiredSecretMakesReadinessUnavailable() throws Exception {
        CatalogPort catalog = mock(CatalogPort.class);
        ManifestRepository manifests = mock(ManifestRepository.class);
        SecretManager secrets = key -> { throw new IllegalStateException("missing"); };
        when(manifests.findAll()).thenReturn(java.util.List.of());
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(mock(com.ai.gateway.domain.model.CatalogSnapshot.class));

        var constructor = java.util.Arrays.stream(HealthController.class.getConstructors())
                .filter(candidate -> java.util.Arrays.equals(candidate.getParameterTypes(),
                        new Class<?>[]{CatalogPort.class, ManifestRepository.class,
                                SecretManager.class, String.class}))
                .findFirst().orElse(null);
        assertThat(constructor).isNotNull();
        HealthController controller = (HealthController) constructor.newInstance(
                catalog, manifests, secrets, "production");
        var response = controller.readiness();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(((java.util.Map<?, ?>) response.getBody().get("checks"))
                .get("requiredSecrets")).isEqualTo("DOWN");
    }
}
