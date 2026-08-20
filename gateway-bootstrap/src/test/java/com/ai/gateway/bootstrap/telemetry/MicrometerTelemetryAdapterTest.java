package com.ai.gateway.bootstrap.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerTelemetryAdapterTest {

    @Test
    void recordValueUpdatesOneLowCardinalityGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerTelemetryAdapter telemetry = new MicrometerTelemetryAdapter(
                ObservationRegistry.create(), registry);

        telemetry.recordValue("gateway.agent.store.entries", 3L,
                Map.of("resource", "turn", "userId", "must-be-dropped"));
        telemetry.recordValue("gateway.agent.store.entries", 7L,
                Map.of("resource", "turn", "userId", "another-user"));

        assertThat(registry.get("gateway.agent.store.entries")
                .tag("resource", "turn").gauge().value()).isEqualTo(7.0d);
        assertThat(registry.find("gateway.agent.store.entries").gauges()).hasSize(1);
    }
}
