package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestDigestTest {

    @Test
    void digestIsStableForEquivalentManifestAndChangesWithContractContent() {
        CapabilityManifest first = manifest("Create order");
        CapabilityManifest equivalent = manifest("Create order");
        CapabilityManifest changed = manifest("Create order with approval");

        assertThat(ManifestDigest.sha256(first)).isEqualTo(ManifestDigest.sha256(equivalent));
        assertThat(ManifestDigest.sha256(first)).isNotEqualTo(ManifestDigest.sha256(changed));
        assertThat(ManifestDigest.sha256(first)).hasSize(64);
    }

    @Test
    void digestShouldDistinguishStringAndNumberConstants() {
        CapabilityManifest stringConstant = manifestWithConstant("1");
        CapabilityManifest numberConstant = manifestWithConstant(1);

        assertThat(ManifestDigest.sha256(stringConstant))
                .isNotEqualTo(ManifestDigest.sha256(numberConstant));
    }

    private static CapabilityManifest manifest(String displayName) {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                "order.create", "1.0.0",
                new CapabilityManifest.Owner("orders", "orders@example.com"),
                List.of("orders", "write"));
        ProtocolBinding binding = new ProtocolBinding(
                Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                "create", List.of("java.lang.String"), "hessian2", List.of(), Map.of());
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                displayName, "Creates an order",
                new CapabilityManifest.Examples(List.of("create order"), List.of(), List.of("order")),
                RiskLevel.WRITE_LOW, Map.of("type", "object"), null, binding, output,
                new ResiliencePolicy(1000L, 0, 1, false));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }

    private static CapabilityManifest manifestWithConstant(Object constantValue) {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                "order.create", "1.0.0",
                new CapabilityManifest.Owner("orders", "orders@example.com"),
                List.of("orders"));
        ArgumentBinding argument = new ArgumentBinding(
                0, "constant", "java.lang.Object", ArgumentSource.CONSTANT,
                null, null, constantValue, null);
        ProtocolBinding binding = new ProtocolBinding(
                Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                "create", List.of("java.lang.Object"), "hessian2",
                List.of(argument), Map.of());
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                "Create order", "Creates an order",
                new CapabilityManifest.Examples(List.of(), List.of(), List.of()),
                RiskLevel.WRITE_LOW, Map.of("type", "object"), null, binding, output,
                new ResiliencePolicy(1000L, 0, 1, false));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }
}
