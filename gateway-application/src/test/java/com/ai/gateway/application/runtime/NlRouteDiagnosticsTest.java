package com.ai.gateway.application.runtime;

import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
import com.ai.gateway.application.catalog.CandidateResolutionService;
import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ModelDecision;
import com.ai.gateway.domain.model.NlRouterMode;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.ai.gateway.domain.service.AliasGenerator;
import com.ai.gateway.domain.service.TextNormalizer;
import com.ai.gateway.domain.service.ThresholdEvaluator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Security and fidelity contract of the admin catalog-diagnostics use case.
 *
 * <p>Diagnostics emits a higher information density than the runtime surface, so it is
 * held to the same constraints: no protocol binding, service address, interface name or
 * confirmation token in the output; no execution; no cross-org observation; fail-closed
 * audit under the {@code gateway-nl-diagnostic} plane.</p>
 */
class NlRouteDiagnosticsTest {

    @Test
    void disabledDeploymentRefusesDiagnosticsWithoutTouchingTheCatalog() {
        Fixture fixture = new Fixture();
        NlRouteDiagnosticsUseCase useCase = fixture.useCase(NlRouterMode.DISABLED);

        NlRouteDiagnosticsUseCase.DiagnosticsReport report = useCase.diagnose(fixture.admin,
                NlRouteDiagnosticsUseCase.DiagnosticsRequest.of("查询订单状态"));

        assertThat(report.status()).isEqualTo(NlRouteDiagnosticsUseCase.Status.DISABLED);
        assertThat(report.errorCode()).isEqualTo(ErrorCode.NL_ROUTER_DISABLED.name());
        verifyNoInteractions(fixture.catalog, fixture.llm);
        // 拒绝路径同样落审计：诊断调用本身是需要留痕的管理面动作。
        verify(fixture.audit).recordEvent(any());
    }

    @Test
    void rejectsRequestsThatDoNotDeclareDryRun() {
        Fixture fixture = new Fixture();

        NlRouteDiagnosticsUseCase.DiagnosticsReport report =
                fixture.useCase(NlRouterMode.DIAGNOSTIC).diagnose(fixture.admin,
                        new NlRouteDiagnosticsUseCase.DiagnosticsRequest(
                                "查询订单状态", List.of(), List.of(), 5, true, false));

        assertThat(report.status()).isEqualTo(NlRouteDiagnosticsUseCase.Status.REJECTED);
        assertThat(report.errorCode())
                .isEqualTo(NlRouteDiagnosticsUseCase.REASON_DRY_RUN_REQUIRED);
        verifyNoInteractions(fixture.catalog, fixture.llm);
    }

    @Test
    void rejectsBlankQuery() {
        Fixture fixture = new Fixture();

        NlRouteDiagnosticsUseCase.DiagnosticsReport report =
                fixture.useCase(NlRouterMode.DIAGNOSTIC).diagnose(fixture.admin,
                        NlRouteDiagnosticsUseCase.DiagnosticsRequest.of("   "));

        assertThat(report.status()).isEqualTo(NlRouteDiagnosticsUseCase.Status.REJECTED);
        assertThat(report.errorCode()).isEqualTo(NlRouteDiagnosticsUseCase.REASON_QUERY_REQUIRED);
    }

    @Test
    void reportsAliasAndProjectionWithoutLeakingBindingDetails() {
        Fixture fixture = new Fixture();

        NlRouteDiagnosticsUseCase.DiagnosticsReport report =
                fixture.useCase(NlRouterMode.DIAGNOSTIC).diagnose(fixture.admin,
                        NlRouteDiagnosticsUseCase.DiagnosticsRequest.of("查询订单状态"));

        assertThat(report.completed()).isTrue();
        assertThat(report.candidates()).hasSize(1);
        NlRouteDiagnosticsUseCase.CandidateView view = report.candidates().get(0);
        assertThat(view.rank()).isEqualTo(1);
        assertThat(view.alias()).startsWith("cap_");
        assertThat(view.capabilityId()).isEqualTo("order.query");
        assertThat(view.manifestDigest()).isNotBlank();
        assertThat(view.risk()).isEqualTo(RiskLevel.READ_ONLY);
        // 可信字段必须出现在剥离清单中，且不出现在模型可见契约里。
        assertThat(view.strippedTrustedFields()).contains("orgId");
        assertThat(view.argumentContract()).doesNotContainKey("orgId");
        assertThat(report.findings())
                .contains(NlRouteDiagnosticsUseCase.Finding.TRUSTED_FIELDS_STRIPPED);

        String rendered = view.toString();
        assertThat(rendered)
                .doesNotContain("com.example.OrderService")
                .doesNotContain("hessian2")
                .doesNotContain("confirmationToken");
    }

    @Test
    void doesNotCallTheModelWhenExplainIsDisabled() {
        Fixture fixture = new Fixture();

        NlRouteDiagnosticsUseCase.DiagnosticsReport report =
                fixture.useCase(NlRouterMode.DIAGNOSTIC).diagnose(fixture.admin,
                        NlRouteDiagnosticsUseCase.DiagnosticsRequest.retrievalOnly("查询订单状态"));

        assertThat(report.modelVerdict().outcome())
                .isEqualTo(NlRouteDiagnosticsUseCase.ModelOutcome.SKIPPED);
        verifyNoInteractions(fixture.llm);
    }

    @Test
    void neverExecutesTheCapabilityAndIssuesNoOperationId() {
        Fixture fixture = new Fixture();

        NlRouteDiagnosticsUseCase.DiagnosticsReport report =
                fixture.useCase(NlRouterMode.DIAGNOSTIC).diagnose(fixture.admin,
                        NlRouteDiagnosticsUseCase.DiagnosticsRequest.of("查询订单状态"));

        assertThat(report.completed()).isTrue();
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(fixture.audit).recordEvent(event.capture());
        assertThat(event.getValue().operationId()).isNull();
        assertThat(event.getValue().detailsJson())
                .isEqualTo(AuditPlane.GATEWAY_NL_DIAGNOSTIC.detailsJson());
    }

    @Test
    void subjectRoleOverrideNeverChangesTheOrganization() {
        Fixture fixture = new Fixture();
        NlRouteDiagnosticsUseCase useCase = fixture.useCase(NlRouterMode.DIAGNOSTIC);

        useCase.diagnose(fixture.admin, new NlRouteDiagnosticsUseCase.DiagnosticsRequest(
                "查询订单状态", List.of("ops"), List.of("order:read"), 5, true, true));

        ArgumentCaptor<Principal> subject = ArgumentCaptor.forClass(Principal.class);
        verify(fixture.authorization).filterVisibleCapabilities(subject.capture(), anyList());
        assertThat(subject.getValue().roles()).containsExactly("ops");
        assertThat(subject.getValue().permissions()).containsExactly("order:read");
        // orgId / 鉴权时间 / 鉴权方式恒沿用管理员身份，跨 org 观察在类型层面不可表达。
        assertThat(subject.getValue().orgId()).isEqualTo(fixture.admin.orgId());
        assertThat(subject.getValue().authMethod()).isEqualTo(fixture.admin.authMethod());
    }

    @Test
    void topKIsCappedByTheDeploymentPolicy() {
        Fixture fixture = new Fixture();
        NlRouteDiagnosticsUseCase useCase = new NlRouteDiagnosticsUseCase(
                fixture.resolutionService(), new CapabilityPublicProjectionService(),
                new AliasGenerator(), new ThresholdEvaluator(), fixture.llm, fixture.audit,
                new NlRouterPolicy(NlRouterMode.DIAGNOSTIC, true, 3));

        useCase.diagnose(fixture.admin, new NlRouteDiagnosticsUseCase.DiagnosticsRequest(
                "查询订单状态", List.of(), List.of(), 100, false, true));

        ArgumentCaptor<Integer> topK = ArgumentCaptor.forClass(Integer.class);
        verify(fixture.retriever).retrieve(anyString(), anyList(), topK.capture());
        assertThat(topK.getValue()).isEqualTo(3);
    }

    @Test
    void reportsIndexStalenessAsAFinding() {
        Fixture fixture = new Fixture();
        NlRouteDiagnosticsUseCase useCase = fixture.useCase(NlRouterMode.DIAGNOSTIC);
        when(fixture.retriever.indexedCatalogVersion()).thenReturn(2L);

        NlRouteDiagnosticsUseCase.DiagnosticsReport report = useCase.diagnose(fixture.admin,
                NlRouteDiagnosticsUseCase.DiagnosticsRequest.retrievalOnly("查询订单状态"));

        assertThat(report.snapshotVersion()).isEqualTo(3L);
        assertThat(report.indexedCatalogVersion()).isEqualTo(2L);
        assertThat(report.findings())
                .contains(NlRouteDiagnosticsUseCase.Finding.RETRIEVAL_INDEX_STALE);
    }

    @Test
    void modelFailureIsAttributedWithoutLeakingProviderText() {
        Fixture fixture = new Fixture();
        NlRouteDiagnosticsUseCase useCase = fixture.useCase(NlRouterMode.DIAGNOSTIC);
        when(fixture.llm.route(anyString(), anyList()))
                .thenThrow(new LlmRouterPort.LlmRoutingException(
                        ErrorCode.LLM_UNAVAILABLE, "provider secret token leaked"));

        NlRouteDiagnosticsUseCase.DiagnosticsReport report = useCase.diagnose(fixture.admin,
                NlRouteDiagnosticsUseCase.DiagnosticsRequest.of("查询订单状态"));

        assertThat(report.modelVerdict().outcome())
                .isEqualTo(NlRouteDiagnosticsUseCase.ModelOutcome.FAILED);
        assertThat(report.modelVerdict().errorCode()).isEqualTo(ErrorCode.LLM_UNAVAILABLE.name());
        assertThat(report.findings())
                .contains(NlRouteDiagnosticsUseCase.Finding.MODEL_UNAVAILABLE);
    }

    @Test
    void detectsWhenTheModelAnswersWithAnAliasOutsideTheCandidateSet() {
        Fixture fixture = new Fixture();
        NlRouteDiagnosticsUseCase useCase = fixture.useCase(NlRouterMode.DIAGNOSTIC);
        when(fixture.llm.route(anyString(), anyList()))
                .thenReturn(new ModelDecision.SelectDecision("cap_forged", Map.of()));

        NlRouteDiagnosticsUseCase.DiagnosticsReport report = useCase.diagnose(fixture.admin,
                NlRouteDiagnosticsUseCase.DiagnosticsRequest.of("查询订单状态"));

        assertThat(report.modelVerdict().outcome())
                .isEqualTo(NlRouteDiagnosticsUseCase.ModelOutcome.ALIAS_MISMATCH);
        assertThat(report.findings())
                .contains(NlRouteDiagnosticsUseCase.Finding.MODEL_ALIAS_MISMATCH);
    }

    /** Every finding must carry an actionable hint for the manifest author. */
    @Test
    void everyFindingCarriesAHint() {
        for (NlRouteDiagnosticsUseCase.Finding finding
                : NlRouteDiagnosticsUseCase.Finding.values()) {
            assertThat(finding.hint()).as("hint for %s", finding).isNotBlank();
        }
    }

    private static final class Fixture {
        private final CatalogPort catalog = mock(CatalogPort.class);
        private final AuthorizationPort authorization = mock(AuthorizationPort.class);
        private final CandidateRetriever retriever = mock(CandidateRetriever.class);
        private final LlmRouterPort llm = mock(LlmRouterPort.class);
        private final AuditPort audit = mock(AuditPort.class);
        private final CapabilityManifest manifest = manifest();
        private final Principal admin = new Principal("admin-1", 7L, List.of("admin"),
                List.of("gateway:admin"), Instant.now(), "JWT");

        private CandidateResolutionService resolutionService() {
            when(catalog.loadCurrentSnapshot("test"))
                    .thenReturn(new CatalogSnapshot(3L, "test", List.of(manifest),
                            "policy-1", "digest"));
            when(authorization.filterVisibleCapabilities(any(), anyList()))
                    .thenReturn(List.of(manifest));
            when(retriever.retrieve(anyString(), anyList(), anyInt()))
                    .thenReturn(List.of(new CandidateRetriever.ScoredCapability(manifest, 2.5)));
            when(retriever.indexedCatalogVersion()).thenReturn(3L);
            // 默认应答：确认阈值选中的那个候选。必须对空候选列表保持防御——
            // Mockito 在 when(...) 阶段会用空列表回放该 answer，脆弱的实现会在
            // 后续测试重新打桩时抛出，从而掩盖真实断言。
            when(llm.route(anyString(), anyList())).thenAnswer(invocation -> {
                List<LlmRouterPort.LlmCandidate> candidates = invocation.getArgument(1);
                String alias = candidates.isEmpty() ? "cap_none" : candidates.get(0).alias();
                return new ModelDecision.SelectDecision(alias, Map.of());
            });
            return new CandidateResolutionService(catalog, authorization, retriever,
                    new TextNormalizer(), "test");
        }

        private NlRouteDiagnosticsUseCase useCase(NlRouterMode mode) {
            return new NlRouteDiagnosticsUseCase(resolutionService(),
                    new CapabilityPublicProjectionService(), new AliasGenerator(),
                    new ThresholdEvaluator(), llm, audit, NlRouterPolicy.of(mode));
        }

        private static CapabilityManifest manifest() {
            CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                    "order.query", "1.0.0",
                    new CapabilityManifest.Owner("orders", "orders@example.com"),
                    List.of("orders", "read"));
            // orgId 来源为 PRINCIPAL：诊断必须把它列入剥离清单，且不得出现在模型可见契约。
            ProtocolBinding invocation = new ProtocolBinding(
                    Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                    "query", List.of("java.lang.String"), "hessian2",
                    List.of(new ArgumentBinding(0, "orderNo", "java.lang.String",
                                    ArgumentSource.MODEL, "orderNo", null, null, null),
                            new ArgumentBinding(1, "orgId", "java.lang.Long",
                                    ArgumentSource.PRINCIPAL, "orgId", null, null, null)),
                    Map.of());
            OutputContract output = new OutputContract(
                    OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
            Map<String, Object> inputSchema = Map.of(
                    "type", "object",
                    "properties", Map.of("orderNo", Map.of("type", "string"),
                            "orgId", Map.of("type", "integer")));
            CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                    "订单查询", "按订单号查询订单状态",
                    new CapabilityManifest.Examples(List.of("查订单"), List.of(), List.of("订单")),
                    RiskLevel.READ_ONLY, inputSchema, null,
                    invocation, output, new ResiliencePolicy(1000L, 0, 1, false));
            return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
        }
    }
}
