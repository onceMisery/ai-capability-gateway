package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.ResiliencePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSnapshotDigestTest {

    @Test
    void digestIsOrderIndependentButChangesWhenManifestContentChanges() {
        CapabilityManifest first = manifest("order.query", "1.0.0", "query");
        CapabilityManifest second = manifest("order.create", "1.0.0", "create");
        CapabilityManifest changed = manifest("order.query", "1.0.0", "changed");
        CatalogSnapshot a = new CatalogSnapshot(7L, "production",
                List.of(first, second), "policy-v7", "ignored");
        CatalogSnapshot reordered = new CatalogSnapshot(7L, "production",
                List.of(second, first), "policy-v7", "ignored");
        CatalogSnapshot different = new CatalogSnapshot(7L, "production",
                List.of(changed), "policy-v7", "ignored");

        assertThat(CatalogSnapshotDigest.sha256(a))
                .isEqualTo(CatalogSnapshotDigest.sha256(reordered))
                .isNotEqualTo(CatalogSnapshotDigest.sha256(different));
    }

    private static CapabilityManifest manifest(String id, String version, String description) {
        return new CapabilityManifest("gateway.ai/v1", "Capability",
                new CapabilityManifest.Metadata(id, version,
                        new CapabilityManifest.Owner("team", "team@example.com"), List.of()),
                new CapabilityManifest.Spec(description, description,
                        new CapabilityManifest.Examples(List.of(description), List.of(), List.of()),
                        RiskLevel.READ_ONLY, Map.of(),
                        new CapabilityManifest.Authorization(List.of(), Map.of()),
                        new ProtocolBinding(Protocol.DUBBO, null, "example.Service", null, null,
                                "invoke", List.of(), "hessian2", List.of(), Map.of()),
                        new OutputContract(OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 1024),
                        new ResiliencePolicy(1000, 0, 1, true)));
    }
}
