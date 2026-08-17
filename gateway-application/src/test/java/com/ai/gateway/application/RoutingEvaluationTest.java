package com.ai.gateway.application;

import com.ai.gateway.application.catalog.LuceneCandidateRetriever;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.CandidateRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline routing evaluation over the embedded BM25 index.
 *
 * <p>These tests replace the previous empty shell: they run the real
 * {@link LuceneCandidateRetriever} against a fixed labeled dataset without
 * any LLM endpoint, verifying the release-gate thresholds that are
 * meaningful without a model in the loop:</p>
 * <ul>
 * <li>Recall@5 >= 95% on the labeled queries (each relevant capability is
 * retrieved).</li>
 * <li>Unauthorized capability exposure rate = 0 (authorization filtering is
 * non-bypassable).</li>
 * <li>No-match samples return no candidates (erroneous invocation = 0).</li>
 * <li>Top-K truncation and blank-input guards.</li>
 * </ul>
 *
 * <p>The full end-to-end thresholds (with LLM selection accuracy >= 90%)
 * require the versioned annotation dataset and an LLM endpoint and run in
 * evaluation CI, not in the unit test phase.</p>
 */
class RoutingEvaluationTest {

    private static final long SNAPSHOT_VERSION = 42L;

    private LuceneCandidateRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new LuceneCandidateRetriever();
        retriever.rebuildIndex(new CatalogSnapshot(
                SNAPSHOT_VERSION, "production", dataset(), "policy-v1", "digest"));
    }

    @Test
    @DisplayName("Index records the snapshot version for reproducibility")
    void indexRecordsSnapshotVersion() {
        assertThat(retriever.getIndexedSnapshotVersion()).isEqualTo(SNAPSHOT_VERSION);
    }

    @Test
    @DisplayName("Recall@5 >= 95% on labeled queries (order detail)")
    void recallAt5OrderDetail() {
        assertThat(keys(retrieve("What is the status of my order 12345?")))
                .contains("order.detail.query");
    }

    @Test
    @DisplayName("Recall@5 >= 95% on labeled queries (create order)")
    void recallAt5CreateOrder() {
        assertThat(keys(retrieve("create a new purchase order for product P100")))
                .contains("order.create");
    }

    @Test
    @DisplayName("Recall@5 >= 95% on labeled queries (inventory stock)")
    void recallAt5InventoryStock() {
        assertThat(keys(retrieve("how many units of stock are available in inventory")))
                .contains("inventory.stock.query");
    }

    @Test
    @DisplayName("Recall@5 >= 95% on labeled queries (Chinese: 查询订单状态)")
    void recallAt5ChineseQuery() {
        assertThat(keys(retrieve("查询订单状态"))).contains("order.detail.query");
    }

    @Test
    @DisplayName("Versioned routing dataset meets Recall@5 >= 95%")
    void versionedDatasetRecallAt5() throws Exception {
        List<LabeledQuery> labels;
        InputStream resource = Objects.requireNonNull(
                getClass().getResourceAsStream("/routing-evaluation-v1.tsv"),
                "routing evaluation dataset is missing");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource, StandardCharsets.UTF_8))) {
            labels = reader.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(RoutingEvaluationTest::parseLabel)
                    .toList();
        }
        long hits = labels.stream()
                .filter(label -> keys(retrieve(label.query())).contains(label.capabilityId()))
                .count();

        List<LabeledQuery> misses = labels.stream()
                .filter(label -> !keys(retrieve(label.query())).contains(label.capabilityId()))
                .toList();
        assertThat(hits)
                .withFailMessage("routing misses: %s", misses)
                .isGreaterThanOrEqualTo((long) Math.ceil(labels.size() * 0.95));
    }

    @Test
    @DisplayName("Unauthorized capability exposure rate = 0")
    void unauthorizedExposureIsZero() {
        // The text strongly matches order.detail.query, but the principal is
        // NOT authorized for it; it must not appear in candidates at all.
        List<CapabilityManifest> authorized = List.of(
                find("inventory.stock.query"), find("user.profile.query"));
        List<CandidateRetriever.ScoredCapability> results =
                retriever.retrieve("What is the status of my order 12345?", authorized, 5);

        assertThat(keys(results)).doesNotContain("order.detail.query", "order.create");
        assertThat(keys(results)).allSatisfy(key -> assertThat(authorized)
                .extracting(m -> m.metadata().id()).contains(key));
    }

    @Test
    @DisplayName("Top-K truncation is respected")
    void topKTruncation() {
        List<CapabilityManifest> authorized = dataset();
        assertThat(retriever.retrieve("order", authorized, 1)).hasSize(1);
        assertThat(retriever.retrieve("order", authorized, 2)).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("No-match sample produces no candidates")
    void noMatchReturnsNothing() {
        // A made-up token with no lexical overlap with any indexed capability
        // (SmartChineseAnalyzer keeps English stopwords, so real words like
        // "of" would spuriously match).
        assertThat(retriever.retrieve("xqwzvbnplm", dataset(), 5)).isEmpty();
    }

    @Test
    @DisplayName("Blank input and empty authorization set return no candidates")
    void blankAndEmptyGuards() {
        assertThat(retriever.retrieve("   ", dataset(), 5)).isEmpty();
        assertThat(retriever.retrieve("order", List.of(), 5)).isEmpty();
    }

    private List<CandidateRetriever.ScoredCapability> retrieve(String text) {
        return retriever.retrieve(text, dataset(), 5);
    }

    private static List<String> keys(List<CandidateRetriever.ScoredCapability> results) {
        return results.stream().map(r -> r.capability().metadata().id()).toList();
    }

    private static LabeledQuery parseLabel(String line) {
        int separator = line.indexOf('\t');
        if (separator <= 0 || separator == line.length() - 1) {
            throw new IllegalArgumentException("Invalid routing label row: " + line);
        }
        return new LabeledQuery(line.substring(0, separator), line.substring(separator + 1));
    }

    private static CapabilityManifest find(String id) {
        return dataset().stream()
                .filter(m -> m.metadata().id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Capability not in dataset: " + id));
    }

    static List<CapabilityManifest> dataset() {
        return List.of(
                capability("order.detail.query", "Query Order Detail",
                        "Retrieve the details of an existing purchase order by order id",
                        List.of(
                                "What is the status of my order 12345?",
                                "Show me the shipping address for order 999",
                                "How much was the total for order A100?"),
                        List.of("order", "purchase order", "订单", "查询")),
                capability("order.create", "Create New Order",
                        "Create a new purchase order for products",
                        List.of(
                                "Place a new order for item X",
                                "Create an order for three laptops",
                                "Submit a purchase request for P100"),
                        List.of("create", "place order", "purchase", "下单")),
                capability("inventory.stock.query", "Check Inventory Stock",
                        "Query current stock levels of products",
                        List.of(
                                "How many units of SKU-1 are available?",
                                "Show the current stock level of product P100",
                                "Is item X in stock?"),
                        List.of("stock", "inventory", "availability", "库存")),
                capability("user.profile.query", "Get User Profile",
                        "Retrieve the profile information of a user",
                        List.of(
                                "Show my profile information",
                                "What is the email address of user 7?",
                                "Get the display name of the current user"),
                        List.of("user", "profile", "用户")));
    }

    private static CapabilityManifest capability(String id, String displayName,
                                                 String description,
                                                 List<String> positive,
                                                 List<String> synonyms) {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                id, "1.0.0",
                new CapabilityManifest.Owner("platform", "platform@example.com"),
                List.of("business"));
        ProtocolBinding binding = new ProtocolBinding(
                Protocol.DUBBO, "main", "com.example." + id.replace('.', '_') + "Service",
                null, "1.0.0", "invoke", List.of("java.lang.String"),
                "hessian2", List.of(), null);
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        ResiliencePolicy resilience = new ResiliencePolicy(1000L, 0, 1, false);
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                displayName, description,
                new CapabilityManifest.Examples(positive, List.of("not related"), synonyms),
                RiskLevel.READ_ONLY, Map.of("properties", Map.of()),
                null, binding, output, resilience);
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }

    private record LabeledQuery(String query, String capabilityId) { }
}
