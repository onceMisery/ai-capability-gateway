package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.runtime.HealthReadinessUseCase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void missingRequiredSecretMakesReadinessUnavailable() throws Exception {
        HealthReadinessUseCase readiness = mock(HealthReadinessUseCase.class);
        when(readiness.check()).thenReturn(new HealthReadinessUseCase.Result(false,
                java.util.Map.of("database", "UP", "activeSnapshot", "UP",
                        "requiredSecrets", "DOWN", "adapterInitialization", "UP")));
        HealthController controller = new HealthController(readiness);
        var response = controller.readiness();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(((java.util.Map<?, ?>) response.getBody().get("checks"))
                .get("requiredSecrets")).isEqualTo("DOWN");
    }
}
