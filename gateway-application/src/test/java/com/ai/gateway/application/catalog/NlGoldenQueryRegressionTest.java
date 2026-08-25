package com.ai.gateway.application.catalog;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.service.TextNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Retrieval-layer regression guard driven by {@code docs/aegis/baseline/nl-golden-queries.json}.
 *
 * <p>Only the deterministic part of the routing chain is asserted here: authorization
 * pre-filter, {@link TextNormalizer} and BM25 ranking. No {@code LlmRouterPort} is
 * involved, so the test costs no tokens and produces the same verdict on every run.
 * The selection layer is validated separately against a real model before a release.</p>
 *
 * <p>The baseline carries only the model-visible manifest text. Protocol bindings,
 * service addresses and interface names are supplied by this test as fixed dummies —
 * they never influence retrieval and must never appear in a baseline file.</p>
 */
class NlGoldenQueryRegressionTest {

    private static final Path BASELINE =
            locateRepositoryRoot().resolve("docs/aegis/baseline/nl-golden-queries.json");

    private static JsonNode baseline;
    private static CandidateResolutionService resolutionService;
    private static Principal principal;

    @BeforeAll
    static void loadBaseline() throws IOException {
        baseline = new ObjectMapper().readTree(Files.readString(BASELINE));

        List<CapabilityManifest> capabilities = new ArrayList<>();
        baseline.get("capabilities").forEach(node -> capabilities.add(toManifest(node)));
        CatalogSnapshot snapshot = new CatalogSnapshot(
                1L, "test", capabilities, "policy-golden", "digest-golden");

        LuceneCandidateRetriever retriever = new LuceneCandidateRetriever();
        retriever.rebuildIndex(snapshot);

        CatalogPort catalogPort = mock(CatalogPort.class);
        when(catalogPort.loadCurrentSnapshot("test")).thenReturn(snapshot);
        AuthorizationPort authorizationPort = mock(AuthorizationPort.class);
        when(authorizationPort.filterVisibleCapabilities(any(), anyList()))
                .thenReturn(capabilities);

        resolutionService = new CandidateResolutionService(catalogPort, authorizationPort,
                retriever, new TextNormalizer(), "test");
        principal = new Principal("golden-user", 1L, List.of("ops"), List.of(),
                Instant.now(), "JWT");
    }

    /** Guards the analyzer version the baseline was recorded against. */
    @Test
    void analyzerVersionMatchesTheBaseline() {
        assertThat(LuceneCandidateRetriever.ANALYZER_VERSION)
                .isEqualTo(baseline.get("analyzerVersion").asText());
    }

    @TestFactory
    List<DynamicTest> goldenQueriesKeepTheirExpectedRank() {
        return StreamSupport.stream(baseline.get("queries").spliterator(), false)
                .map(entry -> {
                    String query = entry.get("query").asText();
                    String expectedId = entry.get("expectedCapabilityId").asText();
                    int maxRank = entry.get("maxRank").asInt();
                    return DynamicTest.dynamicTest(
                            "\"" + query + "\" -> " + expectedId + " within rank " + maxRank,
                            () -> assertRank(query, expectedId, maxRank));
                })
                .toList();
    }

    private static void assertRank(String query, String expectedId, int maxRank) {
        CandidateResolutionService.Resolution resolution =
                resolutionService.resolve(principal, query, Math.max(maxRank, 5));

        assertThat(resolution.resolved())
                .as("resolution outcome for \"%s\" was %s", query, resolution.outcome())
                .isTrue();

        List<String> rankedIds = resolution.candidates().stream()
                .map(candidate -> candidate.capability().metadata().id())
                .toList();
        int actualRank = rankedIds.indexOf(expectedId) + 1;

        assertThat(actualRank)
                .as("\"%s\": expected %s within rank %d but ranking was %s",
                        query, expectedId, maxRank, rankedIds)
                .isBetween(1, maxRank);
    }

    /** Builds a manifest whose model-visible text comes from the baseline entry. */
    private static CapabilityManifest toManifest(JsonNode node) {
        String id = node.get("id").asText();
        String version = node.get("version").asText();
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                id, version, new CapabilityManifest.Owner("golden", "golden@example.com"),
                textList(node.get("tags")));
        CapabilityManifest.Examples examples = new CapabilityManifest.Examples(
                textList(node.get("positive")), textList(node.get("negative")),
                textList(node.get("synonyms")));
        // 协议绑定是检索无关的固定占位值：基线文件只维护模型可见文本，
        // 绝不承载服务地址与接口名。
        ProtocolBinding invocation = new ProtocolBinding(
                Protocol.DUBBO, "golden", "com.example.GoldenService", null, "1.0.0",
                "invoke", List.of("java.lang.String"), "hessian2", List.of(), Map.of());
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                node.get("displayName").asText(), node.get("description").asText(), examples,
                RiskLevel.valueOf(node.get("risk").asText()),
                inputSchema(textList(node.get("fieldNames"))), null,
                invocation, output, new ResiliencePolicy(1000L, 0, 1, false));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }

    private static Map<String, Object> inputSchema(List<String> fieldNames) {
        Map<String, Object> properties = new LinkedHashMap<>();
        fieldNames.forEach(name -> properties.put(name, Map.of("type", "string")));
        return Map.of("type", "object", "properties", properties);
    }

    private static List<String> textList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    /** Walks up from the working directory until the repository root pom is found. */
    private static Path locateRepositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("docs/aegis/baseline"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root with docs/aegis/baseline not found");
    }

    /** Documents that the retriever the baseline runs against is the production one. */
    @Test
    void baselineRunsAgainstTheProductionRetriever() {
        assertThat(resolutionService).isNotNull();
        assertThat(CandidateRetriever.class)
                .isAssignableFrom(LuceneCandidateRetriever.class);
    }
}
