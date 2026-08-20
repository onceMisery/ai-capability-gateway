package com.ai.gateway.application.agent;

import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.FieldBinding;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AgentTestFixtures {

    private AgentTestFixtures() {
    }

    static Principal principal(String subject) {
        return new Principal(subject, 7L, List.of("user"), List.of("orders:detail:read"),
                Instant.parse("2026-08-19T00:00:00Z"), "JWT");
    }

    static CapabilityManifest manifest(String id, RiskLevel risk, String description) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("orderNo", Map.of(
                "type", "string", "description", "Order number"));
        properties.put("orgId", Map.of("type", "integer"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("orderNo", "orgId"));
        schema.put("additionalProperties", false);
        schema.put("properties", properties);

        ArgumentBinding request = new ArgumentBinding(
                0, "request", "com.example.OrderRequest", null, null, null, null,
                Map.of(
                        "/orderNo", new FieldBinding(
                                ArgumentSource.MODEL, "/orderNo", null, null),
                        "/orgId", new FieldBinding(
                                ArgumentSource.PRINCIPAL, "/orgId", null, null)));
        ProtocolBinding binding = new ProtocolBinding(
                Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                "execute", List.of("com.example.OrderRequest"), "hessian2",
                List.of(request), Map.of());
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        return new CapabilityManifest("gateway.ai/v1", "Capability",
                new CapabilityManifest.Metadata(id, "1.0.0",
                        new CapabilityManifest.Owner("orders", "orders@example.com"),
                        List.of("orders")),
                new CapabilityManifest.Spec(
                        "Order detail", description,
                        new CapabilityManifest.Examples(
                                List.of("query order detail"),
                                List.of("delete order"), List.of("order")),
                        risk, schema, null, binding, output,
                        new ResiliencePolicy(1000L, 0, 1, false)));
    }

    static CatalogSnapshot snapshot(long version, CapabilityManifest... manifests) {
        CatalogSnapshot unsigned = new CatalogSnapshot(
                version, "production", List.of(manifests), "policy-v1", "pending");
        return new CatalogSnapshot(version, "production", List.of(manifests), "policy-v1",
                CatalogSnapshotDigest.sha256(unsigned));
    }
}
