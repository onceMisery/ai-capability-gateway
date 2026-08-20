package com.ai.gateway.application.agent;

import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentResolveAdmissionControllerTest {

    @Test
    void rejectsWithoutQueueingAndReleasesPermitIdempotently() {
        AgentResolveAdmissionController admission =
                new AgentResolveAdmissionController(1, mock(TelemetryPort.class));

        AgentResolveAdmissionController.Permit first = admission.tryAcquire();

        assertThat(first).isNotNull();
        assertThat(admission.inFlight()).isEqualTo(1);
        assertThat(admission.tryAcquire()).isNull();

        first.close();
        first.close();
        assertThat(admission.inFlight()).isZero();
        assertThat(admission.tryAcquire()).isNotNull();
    }
}
