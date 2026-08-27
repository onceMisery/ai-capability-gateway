package com.ai.gateway.application.runtime;

import com.ai.gateway.application.agent.AgentCapabilityResolver;
import com.ai.gateway.application.agent.ToolReferenceService;
import com.ai.gateway.application.catalog.AgentCandidateRanker;
import com.ai.gateway.application.catalog.AuthorizedCandidateRetrieval;
import com.ai.gateway.application.catalog.CandidateResolutionService;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityReference;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.PolicySnapshot;
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
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.AliasGenerator;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;
import com.ai.gateway.domain.service.TextNormalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cross-plane equivalence tests for candidate retrieval (design §2.4).
 *
 * <p>网关有三个消费候选的入口：MCP 工具目录（本类被测对象）、Host 侧 capability resolve
 * （{@link AgentCapabilityResolver}）与运行面自然语言路由（{@link CandidateResolutionService}）。
 * 本类断言的不是「某个入口返回了什么」，而是「三个入口之间没有独立的检索链路」——
 * 这是安全要求而不仅是去重要求：每一套独立检索都是一套独立的授权过滤，
 * 其中任何一处遗漏都是越权泄漏，且通常只表现为「排序略有不同」，不表现为报错。</p>
 *
 * <p>所有断言都建立在同一个 {@link RecordingRetriever} 上：它记录每次被要求搜索的域，
 * 并<b>逆序</b>返回结果。逆序是刻意的——若某个入口的最终顺序等于检索器返回顺序，
 * 说明该入口没走共用重排器。</p>
 *
 * @author cmiracle@163.com
 */
class AgentToolCatalogUseCaseTest {

    private static final String QUERY = "order detail";
    private static final long POLICY_EPOCH = 42L;

    private final CapabilityManifest detail = manifest(
            "orders.detail.query", RiskLevel.READ_ONLY, "query one order");
    private final CapabilityManifest list = manifest(
            "orders.list.query", RiskLevel.READ_ONLY, "list orders");
    private final CapabilityManifest refund = manifest(
            "orders.refund.apply", RiskLevel.WRITE_LOW, "apply a refund");
    private final CapabilityManifest purge = manifest(
            "orders.purge.execute", RiskLevel.WRITE_HIGH, "purge order data");

    private final RecordingRetriever retriever = new RecordingRetriever(Map.of(
            "orders.detail.query", 5.0d,
            "orders.list.query", 5.0d,
            "orders.refund.apply", 4.0d,
            // 刻意给高危写操作最高分：排除它靠的是风险档判定，不是它恰好排在后面。
            "orders.purge.execute", 9.0d));

    private final AuthorizedCandidateRetrieval retrieval =
            new AuthorizedCandidateRetrieval(retriever, new TextNormalizer());
    private final AgentCandidateRanker ranker = new AgentCandidateRanker(new TextNormalizer());

    private final Principal principal = principal();
    private final CatalogPort catalogPort = mock(CatalogPort.class);
    private final AuthenticationPort authenticationPort = mock(AuthenticationPort.class);
    private final AuthorizationPort authorizationPort = mock(AuthorizationPort.class);
    private final ExecutorService resolveExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        resolveExecutor.shutdownNow();
    }

    // ------------------------------------------- 两个 Agent 入口的候选与排序必须逐项相同

    @Test
    void bothAgentPlanesReturnTheSameCandidatesInTheSameOrder() {
        InMemoryCatalogManager manager = activatedCatalog(detail, list, refund, purge);
        grantVisibility(CapabilityVisibility.all(POLICY_EPOCH), List.of());

        List<String> mcpOrder = displayNames(mcpPlane(manager)
                .resolve(RequestContext.empty(), QUERY, 3));
        List<String> hostOrder = hostPlane(manager)
                .resolve(principal, QUERY, 3)
                .candidates().stream()
                .map(AgentCapabilityResolver.Candidate::displayName)
                .toList();

        // 检索器逆序返回（refund, list, detail），最终顺序却是分数降序 + id 升序打破并列，
        // 说明两个入口都经过了共用重排器，而不是各自沿用检索器返回顺序。
        assertThat(mcpOrder).containsExactly(
                "orders.detail.query", "orders.list.query", "orders.refund.apply");
        // 这就是 §2.4 的验证要求：同一 Principal、同一 query，两个 Agent 入口逐项相同。
        // （Host 平面另有 2 KiB 模型上下文预算，本例三条候选未触及该上限。）
        assertThat(hostOrder).isEqualTo(mcpOrder);
    }

    @Test
    void neitherAgentPlaneSurfacesAHighRiskWriteCapability() {
        InMemoryCatalogManager manager = activatedCatalog(detail, list, refund, purge);
        grantVisibility(CapabilityVisibility.all(POLICY_EPOCH), List.of());

        List<String> mcpOrder = displayNames(mcpPlane(manager)
                .resolve(RequestContext.empty(), QUERY, 5));
        List<String> mcpScope = retriever.lastSearchedScope();
        hostPlane(manager).resolve(principal, QUERY, 5);
        List<String> hostScope = retriever.lastSearchedScope();

        // WRITE_HIGH 不是「排在最后」而是「不参与打分」：它连检索域都进不去，
        // 因此不可能因为某次分数波动而挤进模型上下文。
        assertThat(mcpOrder).doesNotContain("orders.purge.execute");
        assertThat(mcpScope).doesNotContain("orders.purge.execute");
        assertThat(hostScope).doesNotContain("orders.purge.execute");
        assertThat(hostScope).isEqualTo(mcpScope);
    }

    // ------------------------------------------------- 三个入口的检索域都等于已授权子集

    @Test
    void anUnauthorizedCapabilityNeverEntersScoringOnAnyPlane() {
        InMemoryCatalogManager manager = activatedCatalog(detail, list, refund, purge);
        CapabilityVisibility restricted = CapabilityVisibility.restricted(
                POLICY_EPOCH, Set.of(CapabilityReference.from(detail)));
        grantVisibility(restricted, List.of(detail));

        mcpPlane(manager).resolve(RequestContext.empty(), QUERY, 5);
        List<String> mcpScope = retriever.lastSearchedScope();
        hostPlane(manager).resolve(principal, QUERY, 5);
        List<String> hostScope = retriever.lastSearchedScope();
        snapshotPlane().resolve(principal, QUERY, 5);
        List<String> snapshotScope = retriever.lastSearchedScope();

        // 断言的是「检索域」而不是「返回结果」：未授权能力若参与了打分与 Top-K 截断，
        // 即便最终没被返回，也已经影响了排序，且说明该入口的过滤发生在错误的位置。
        assertThat(mcpScope).containsExactly("orders.detail.query");
        assertThat(hostScope).containsExactly("orders.detail.query");
        assertThat(snapshotScope).containsExactly("orders.detail.query");
    }

    @Test
    void theSnapshotPlaneRetrievesTheSameAuthorizedCandidateSetAsTheAgentPlanes() {
        InMemoryCatalogManager manager = activatedCatalog(detail, list, refund, purge);
        grantVisibility(CapabilityVisibility.all(POLICY_EPOCH),
                List.of(detail, list, refund, purge));

        List<String> mcpOrder = displayNames(mcpPlane(manager)
                .resolve(RequestContext.empty(), QUERY, 5));
        CandidateResolutionService.Resolution nl = snapshotPlane().resolve(principal, QUERY, 5);

        assertThat(nl.outcome()).isEqualTo(CandidateResolutionService.Outcome.RESOLVED);
        // 快照面刻意<b>不</b>排除 WRITE_HIGH——运行面允许经确认通道路由高危写操作。
        // 去掉这一档刻意的差异后，两侧候选集合必须逐一相同：它们共用同一个检索内核。
        assertThat(nl.candidates().stream()
                .map(scored -> scored.capability().metadata().id())
                .filter(id -> !id.equals("orders.purge.execute"))
                .toList())
                .containsExactlyInAnyOrderElementsOf(mcpOrder);
    }

    private static List<String> displayNames(AgentToolCatalogUseCase.Resolution resolution) {
        return resolution.candidates().stream()
                .map(AgentToolCatalogUseCase.Candidate::displayName)
                .toList();
    }

    // ------------------------------------------------------------------ 装配

    private InMemoryCatalogManager activatedCatalog(CapabilityManifest... manifests) {
        when(catalogPort.loadCurrentSnapshot("production")).thenReturn(snapshot(manifests));
        InMemoryCatalogManager manager = new InMemoryCatalogManager(catalogPort);
        assertThat(manager.loadAndActivate("production")).isTrue();
        return manager;
    }

    private AgentToolCatalogUseCase mcpPlane(InMemoryCatalogManager manager) {
        return new AgentToolCatalogUseCase(authenticationPort, authorizationPort, manager,
                retrieval, ranker, new AliasGenerator(), "production");
    }

    private AgentCapabilityResolver hostPlane(InMemoryCatalogManager manager) {
        return new AgentCapabilityResolver(authenticationPort, authorizationPort, manager,
                retrieval, ranker,
                new ToolReferenceService("k1", key(), null, null, 120L),
                mock(TelemetryPort.class), resolveExecutor, 5_000L);
    }

    private CandidateResolutionService snapshotPlane() {
        return new CandidateResolutionService(catalogPort, authorizationPort,
                retrieval, "production");
    }

    private void grantVisibility(CapabilityVisibility visibility,
                                 List<CapabilityManifest> snapshotVisible) {
        when(authenticationPort.authenticate(any())).thenReturn(principal);
        when(authorizationPort.resolveVisibility(principal)).thenReturn(visibility);
        when(authorizationPort.resolvePolicySnapshot(principal))
                .thenReturn(PolicySnapshot.from(visibility));
        when(authorizationPort.filterVisibleCapabilities(any(), any()))
                .thenReturn(snapshotVisible);
    }

    private static byte[] key() {
        return "convergence-0123456789abcdef0123456789abcdef"
                .substring(0, 32).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A deterministic retriever that records every scope it was asked to search and
     * returns results in reverse scope order.
     *
     * <p>不实现 {@code CatalogBoundCandidateRetriever}：本类比较的是三个入口的行为一致性，
     * 而非 Lucene 的绑定路径，因此让两个 Agent 入口都走非绑定路径以消除这一变量。</p>
     */
    private static final class RecordingRetriever implements CandidateRetriever {

        private final Map<String, Double> scores;
        private final List<List<String>> searchedScopes = new ArrayList<>();

        private RecordingRetriever(Map<String, Double> scores) {
            this.scores = Map.copyOf(scores);
        }

        @Override
        public List<ScoredCapability> retrieve(String normalizedText,
                                               List<CapabilityManifest> authorizedCapabilities,
                                               int topK) {
            searchedScopes.add(authorizedCapabilities.stream()
                    .map(manifest -> manifest.metadata().id())
                    .toList());
            List<ScoredCapability> scored = new ArrayList<>();
            for (CapabilityManifest manifest : authorizedCapabilities) {
                scored.add(new ScoredCapability(manifest,
                        scores.getOrDefault(manifest.metadata().id(), 1.0d)));
            }
            Collections.reverse(scored);
            return List.copyOf(scored.subList(0, Math.min(topK, scored.size())));
        }

        @Override
        public long indexedCatalogVersion() {
            return 8L;
        }

        private List<String> lastSearchedScope() {
            return searchedScopes.get(searchedScopes.size() - 1);
        }
    }

    // ------------------------------------------------------------------ 数据

    private static Principal principal() {
        return new Principal("user-1", 7L, List.of("user"), List.of(),
                Instant.now(), "JWT");
    }

    private static CatalogSnapshot snapshot(CapabilityManifest... manifests) {
        CatalogSnapshot unsigned = new CatalogSnapshot(8L, "production", List.of(manifests),
                "policy-8", "pending");
        return new CatalogSnapshot(8L, "production", List.of(manifests), "policy-8",
                CatalogSnapshotDigest.sha256(unsigned));
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
