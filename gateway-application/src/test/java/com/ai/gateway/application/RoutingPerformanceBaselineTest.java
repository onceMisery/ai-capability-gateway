package com.ai.gateway.application;

import com.ai.gateway.application.catalog.LuceneCandidateRetriever;
import com.ai.gateway.domain.model.CatalogSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Repeatable local baseline for the in-process candidate retrieval path. */
class RoutingPerformanceBaselineTest {

    @Test
    void recordsCandidateRetrievalBaseline() {
        LuceneCandidateRetriever retriever = new LuceneCandidateRetriever();
        List<com.ai.gateway.domain.model.CapabilityManifest> capabilities = RoutingEvaluationTest.dataset();
        retriever.rebuildIndex(new CatalogSnapshot(42L, "production", capabilities, "policy-v1", "digest"));
        String[] queries = {"order status", "create purchase order", "inventory stock", "user profile"};

        for (int i = 0; i < 100; i++) {
            retriever.retrieve(queries[i % queries.length], capabilities, 5);
        }
        long started = System.nanoTime();
        int resultCount = 0;
        for (int i = 0; i < 1_000; i++) {
            resultCount += retriever.retrieve(queries[i % queries.length], capabilities, 5).size();
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        System.out.printf("PERF_BASELINE retrievalIterations=1000 resultCount=%d elapsedMs=%d%n",
                resultCount, elapsedMs);
        assertThat(resultCount).isPositive();
        assertThat(elapsedMs).isLessThan(10_000L);
    }
}
