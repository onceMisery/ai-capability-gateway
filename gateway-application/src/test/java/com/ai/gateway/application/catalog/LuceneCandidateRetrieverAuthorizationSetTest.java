package com.ai.gateway.application.catalog;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneCandidateRetrieverAuthorizationSetTest {

    @Test
    void authorizationSetAboveBooleanClauseLimitStillRetrieves() {
        List<CapabilityManifest> capabilities = IntStream.range(0, 1_100)
                .mapToObj(index -> ActiveCatalogViewTest.manifest(
                        "orders.query-" + index))
                .toList();
        LuceneCandidateRetriever retriever = new LuceneCandidateRetriever();
        retriever.rebuildIndex(new CatalogSnapshot(
                8L, "production", capabilities, "policy", "digest"));

        var results = retriever.retrieve("find order", capabilities, 5);

        assertThat(results).hasSize(5);
        assertThat(retriever.indexedCatalogVersion()).isEqualTo(8L);
    }
}
