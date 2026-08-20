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

    @Test
    void filtersAuthorizationAndHighRiskBeforeBm25Retrieval() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CatalogPort catalog = mock(CatalogPort.class);
        CandidateRetriever retriever = mock(CandidateRetriever.class);
        Principal principal = principal();
        CapabilityManifest visibleRead = manifest("orders.query", RiskLevel.READ_ONLY, "query");
        CapabilityManifest visibleWrite = manifest("orders.cancel", RiskLevel.WRITE_LOW, "cancel");
        CapabilityManifest visibleHigh = manifest("orders.delete", RiskLevel.WRITE_HIGH, "delete");
        CatalogSnapshot snapshot = snapshot(visibleRead, visibleWrite, visibleHigh);

        when(authentication.authenticate(any())).thenReturn(principal);
        when(catalog.loadCurrentSnapshot("production")).thenReturn(snapshot);
        when(authorization.resolveVisibility(principal)).thenReturn(CapabilityVisibility.all(42L));
        when(retriever.retrieve(anyString(), anyList(), eq(20)))
                .thenReturn(List.of(
                        new CandidateRetriever.ScoredCapability(visibleRead, 4.0),
                        new CandidateRetriever.ScoredCapability(visibleWrite, 3.0)));

        AgentToolCatalogUseCase useCase = new AgentToolCatalogUseCase(
                authentication, authorization, catalog, retriever,
                new TextNormalizer(), new AliasGenerator(), "production", 5, 16 * 1024);

        AgentToolCatalogUseCase.Resolution result = useCase.resolve(
                RequestContext.empty(), "查询订单", 99);

        assertThat(result.snapshotVersion()).isEqualTo(8L);
        assertThat(result.policyEpoch()).isEqualTo(42L);
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.toolName()).startsWith("cap_");
            assertThat(candidate.toolName()).doesNotContain("orders");
        });
        assertThat(result.bindings()).extracting(AgentToolCatalogUseCase.Binding::capabilityId)
                .containsExactly("orders.query", "orders.cancel");
        assertThat(result.candidates().get(0).executionMode()).isEqualTo("DIRECT");
        assertThat(result.candidates().get(1).executionMode())
                .isEqualTo("CONFIRMATION_REQUIRED");
        verify(retriever).retrieve(anyString(),
                org.mockito.ArgumentMatchers.argThat(capabilities ->
                        capabilities.size() == 2 && !capabilities.contains(visibleHigh)), eq(20));
    }

    @Test
    void skipsCandidateWhoseModelSchemaExceedsBudget() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CatalogPort catalog = mock(CatalogPort.class);
        CandidateRetriever retriever = mock(CandidateRetriever.class);
        Principal principal = principal();
        CapabilityManifest oversized = manifest("orders.bulk", RiskLevel.READ_ONLY, "x".repeat(200));
        CatalogSnapshot snapshot = snapshot(oversized);

        when(authentication.authenticate(any())).thenReturn(principal);
        when(catalog.loadCurrentSnapshot("production")).thenReturn(snapshot);
        when(authorization.resolveVisibility(principal)).thenReturn(CapabilityVisibility.all(42L));
        when(retriever.retrieve(anyString(), anyList(), eq(20)))
                .thenReturn(List.of(new CandidateRetriever.ScoredCapability(oversized, 4.0)));

        AgentToolCatalogUseCase useCase = new AgentToolCatalogUseCase(
                authentication, authorization, catalog, retriever,
                new TextNormalizer(), new AliasGenerator(), "production", 5, 128);

        AgentToolCatalogUseCase.Resolution result = useCase.resolve(
                RequestContext.empty(), "批量查询", 5);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.bindings()).isEmpty();
    }

    @Test
    void blankQueryDoesNotInvokeRetriever() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CatalogPort catalog = mock(CatalogPort.class);
        CandidateRetriever retriever = mock(CandidateRetriever.class);
        Principal principal = principal();
        CapabilityManifest manifest = manifest("orders.query", RiskLevel.READ_ONLY, "query");

        when(authentication.authenticate(any())).thenReturn(principal);
        when(catalog.loadCurrentSnapshot("production")).thenReturn(snapshot(manifest));
        when(authorization.resolveVisibility(principal)).thenReturn(CapabilityVisibility.all(42L));

        AgentToolCatalogUseCase useCase = new AgentToolCatalogUseCase(
                authentication, authorization, catalog, retriever,
                new TextNormalizer(), new AliasGenerator(), "production");

        AgentToolCatalogUseCase.Resolution result = useCase.resolve(
                RequestContext.empty(), "  ", 5);

        assertThat(result.candidates()).isEmpty();
        verify(retriever, never()).retrieve(anyString(), anyList(), eq(5));
    }

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
