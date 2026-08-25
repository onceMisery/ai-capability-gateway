package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.service.AliasGenerator;
import com.ai.gateway.domain.service.TextNormalizer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolCatalogUseCaseTest {


    private static Principal principal() {
        return new Principal("user-1", 7L, List.of("user"), List.of(),
                Instant.now(), "JWT");
    }

    private static CatalogSnapshot snapshot(CapabilityManifest... manifests) {
        return new CatalogSnapshot(8L, "production", List.of(manifests),
                "policy-8", "snapshot-digest");
    }

    private static CapabilityManifest manifest(String id, RiskLevel risk, String description) {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                id, "1.0.0",
                new CapabilityManifest.Owner("orders", "orders@example.com"), List.of("orders"));
        ProtocolBinding binding = new ProtocolBinding(
                Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                "query", List.of(), "hessian2", List.of(), Map.of());
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", description);
        schema.put("additionalProperties", false);
        schema.put("properties", Map.of("orderNo", Map.of("type", "string")));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata,
                new CapabilityManifest.Spec(
                        id, description,
                        new CapabilityManifest.Examples(List.of(description), List.of(), List.of("order")),
                        risk, schema, null, binding, output,
                        new ResiliencePolicy(1000L, 0, 1, false)));
    }
}
