package com.ai.gateway.application.catalog;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityReference;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveCatalogViewTest {

    @Test
    void resolvesRestrictedVisibilityThroughImmutableOrdinalIndex() {
        CapabilityManifest first = manifest("orders.first");
        CapabilityManifest second = manifest("orders.second");
        ActiveCatalogView view = ActiveCatalogView.from(new CatalogSnapshot(
                8L, "production", List.of(first, second), "policy", "digest"));

        List<CapabilityManifest> visible = view.visibleCapabilities(
                CapabilityVisibility.restricted(42L,
                        Set.of(new CapabilityReference("orders.second", "1.0.0"))));

        assertThat(visible).containsExactly(second);
        assertThat(view.find("orders.first", "1.0.0")).contains(first);
    }

    @Test
    void duplicateCapabilityBindingPreventsViewPublication() {
        CapabilityManifest duplicate = manifest("orders.duplicate");
        CatalogSnapshot snapshot = new CatalogSnapshot(
                8L, "production", List.of(duplicate, duplicate), "policy", "digest");

        assertThatThrownBy(() -> ActiveCatalogView.from(snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate capability binding");
    }

    @Test
    void retiredViewKeepsItsIndexUntilRequestLeaseIsReleased() {
        CapabilityManifest capability = manifest("orders.lease");
        CatalogSnapshot snapshot = new CatalogSnapshot(
                9L, "production", List.of(capability), "policy", "digest");
        LuceneCandidateRetriever retriever = new LuceneCandidateRetriever();
        LuceneCandidateRetriever.IndexHandle index = retriever.buildIndex(snapshot);
        ActiveCatalogView view = ActiveCatalogView.from(
                snapshot, new com.ai.gateway.application.agent.CapabilityPublicProjectionService(), index);
        ActiveCatalogView.ViewLease lease = view.acquireLease();

        view.retire();

        assertThat(retriever.retrieve("find order", view, List.of(capability), 1))
                .hasSize(1);
        lease.close();
        assertThat(retriever.retrieve("find order", view, List.of(capability), 1))
                .isEmpty();
    }

    static CapabilityManifest manifest(String id) {
        ProtocolBinding binding = new ProtocolBinding(
                Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                "query", List.of(), "hessian2", List.of(), Map.of());
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        return new CapabilityManifest("gateway.ai/v1", "Capability",
                new CapabilityManifest.Metadata(id, "1.0.0",
                        new CapabilityManifest.Owner("orders", "orders@example.com"),
                        List.of("orders")),
                new CapabilityManifest.Spec(
                        "Order query", "Find order detail",
                        new CapabilityManifest.Examples(
                                List.of("find order"), List.of(), List.of("order")),
                        RiskLevel.READ_ONLY,
                        Map.of("type", "object", "properties", Map.of()),
                        null, binding, output,
                        new ResiliencePolicy(1000L, 0, 1, false)));
    }
}
