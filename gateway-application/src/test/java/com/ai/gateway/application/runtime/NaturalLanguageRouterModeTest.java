package com.ai.gateway.application.runtime;

import com.ai.gateway.application.catalog.CandidateResolutionService;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.NlRouterMode;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.InteractionRepository;
import com.ai.gateway.domain.service.TextNormalizer;
import com.ai.gateway.domain.service.ThresholdEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the four {@link NlRouterMode} states on the runtime natural-language surface.
 *
 * <p>The freeze introduced by the positioning remediation is an <em>exposure</em> control,
 * not a deletion: {@code DIAGNOSTIC} and {@code DISABLED} close the HTTP surface while the
 * selection kernel stays reachable from the admin diagnostics path. These tests pin that
 * distinction so a future refactor cannot silently turn the freeze into a removal.</p>
 */
class NaturalLanguageRouterModeTest {

    @Test
    void diagnosticModeRejectsRuntimeQueriesBeforeAuthentication() {
        Fixture fixture = new Fixture();

        NaturalLanguageQueryUseCase.QueryResult result =
                fixture.useCase(NlRouterMode.DIAGNOSTIC)
                        .execute(RequestContext.empty(), "req-diag", "查询订单状态", "zh-CN", "UTC");

        assertThat(result.status()).isEqualTo(NaturalLanguageQueryUseCase.QueryStatus.ERROR);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.NL_ROUTER_DISABLED.name());
        // 闸门必须在认证与目录之前生效：既不产生 LLM 成本，也不泄漏目录存在性。
        verifyNoInteractions(fixture.authentication, fixture.catalog, fixture.audit,
                fixture.retriever, fixture.interactions);
    }

    @Test
    void disabledModeRejectsRuntimeQueriesBeforeAuthentication() {
        Fixture fixture = new Fixture();

        NaturalLanguageQueryUseCase.QueryResult result =
                fixture.useCase(NlRouterMode.DISABLED)
                        .execute(RequestContext.empty(), "req-off", "查询订单状态", "zh-CN", "UTC");

        assertThat(result.errorCode()).isEqualTo(ErrorCode.NL_ROUTER_DISABLED.name());
        verifyNoInteractions(fixture.authentication, fixture.catalog, fixture.audit);
    }

    @Test
    void fullModeCreatesClarificationSession() {
        Fixture fixture = new Fixture();
        fixture.ambiguous = true;

        NaturalLanguageQueryUseCase.QueryResult result =
                fixture.useCase(NlRouterMode.FULL)
                        .execute(RequestContext.empty(), "req-full", "订单", "zh-CN", "UTC");

        assertThat(result.status())
                .isEqualTo(NaturalLanguageQueryUseCase.QueryStatus.CLARIFICATION_REQUIRED);
        assertThat(result.interactionId()).isNotBlank();
        assertThat(result.expiresAt()).isNotNull();
        verify(fixture.interactions).save(any());
    }

    @Test
    void compatModeReturnsSingleShotClarificationWithoutASession() {
        Fixture fixture = new Fixture();
        fixture.ambiguous = true;

        NaturalLanguageQueryUseCase.QueryResult result =
                fixture.useCase(NlRouterMode.COMPAT)
                        .execute(RequestContext.empty(), "req-compat", "订单", "zh-CN", "UTC");

        assertThat(result.status())
                .isEqualTo(NaturalLanguageQueryUseCase.QueryStatus.CLARIFICATION_REQUIRED);
        // 单回合语义：仍然提示需要澄清，但澄清对话状态不回到网关。
        assertThat(result.interactionId()).isNull();
        assertThat(result.expiresAt()).isNull();
        verify(fixture.interactions, never()).save(any());
    }

    @Test
    void compatModeStillServesTheRuntimeSurface() {
        Fixture fixture = new Fixture();
        when(fixture.selectProcessor.process(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                any()))
                .thenReturn(new NaturalLanguageQueryUseCase.QueryResult(
                        NaturalLanguageQueryUseCase.QueryStatus.COMPLETED, Map.of("ok", true),
                        null, null, 3L, null, null));

        NaturalLanguageQueryUseCase.QueryResult result =
                fixture.useCase(NlRouterMode.COMPAT)
                        .execute(RequestContext.empty(), "req-ok", "查询订单状态", "zh-CN", "UTC");

        assertThat(result.status()).isEqualTo(NaturalLanguageQueryUseCase.QueryStatus.COMPLETED);
        verify(fixture.authentication).authenticate(any());
    }

    /** The kernel stays wired in every mode; only the exposure policy differs. */
    @Test
    void diagnosticsStayAvailableWhileTheRuntimeSurfaceIsClosed() {
        assertThat(NlRouterPolicy.of(NlRouterMode.DIAGNOSTIC).runtimeQueryAllowed()).isFalse();
        assertThat(NlRouterPolicy.of(NlRouterMode.DIAGNOSTIC).diagnosticsAllowed()).isTrue();
        assertThat(NlRouterPolicy.of(NlRouterMode.DISABLED).diagnosticsAllowed()).isFalse();
        // 部署侧显式打开也不能让 DISABLED 档获得诊断能力（归一化收在策略一处）。
        assertThat(new NlRouterPolicy(NlRouterMode.DISABLED, true, 10).diagnosticsAllowed())
                .isFalse();
    }

    private static final class Fixture {
        private final AuthenticationPort authentication = mock(AuthenticationPort.class);
        private final AuthorizationPort authorization = mock(AuthorizationPort.class);
        private final CatalogPort catalog = mock(CatalogPort.class);
        private final CandidateRetriever retriever = mock(CandidateRetriever.class);
        private final AuditPort audit = mock(AuditPort.class);
        private final InteractionRepository interactions = mock(InteractionRepository.class);
        private final SelectDecisionProcessor selectProcessor = mock(SelectDecisionProcessor.class);
        private final CapabilityManifest manifest = manifest("order.query");
        private boolean ambiguous;

        private NaturalLanguageQueryUseCase useCase(NlRouterMode mode) {
            Principal principal = new Principal("user-1", 7L, List.of("user"), List.of(),
                    Instant.now(), "JWT");
            when(authentication.authenticate(any())).thenReturn(principal);
            when(catalog.loadCurrentSnapshot("test"))
                    .thenReturn(new CatalogSnapshot(3L, "test", List.of(manifest),
                            "policy-1", "digest"));
            when(authorization.filterVisibleCapabilities(any(), anyList()))
                    .thenReturn(List.of(manifest));
            // 分差 0.1 < minTop1Top2ScoreDiff(0.5) 时判定为 CLARIFY。
            when(retriever.retrieve(anyString(), anyList(), anyInt()))
                    .thenReturn(ambiguous
                            ? List.of(new CandidateRetriever.ScoredCapability(manifest, 2.0),
                                    new CandidateRetriever.ScoredCapability(
                                            manifest("order.cancel"), 1.9))
                            : List.of(new CandidateRetriever.ScoredCapability(manifest, 2.0)));

            return new NaturalLanguageQueryUseCase(authentication,
                    new CandidateResolutionService(catalog, authorization, retriever,
                            new TextNormalizer(), "test"),
                    audit, new ThresholdEvaluator(), interactions, selectProcessor,
                    NlRouterPolicy.of(mode));
        }

        private static CapabilityManifest manifest(String id) {
            CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                    id, "1.0.0", new CapabilityManifest.Owner("orders", "orders@example.com"),
                    List.of("orders"));
            ProtocolBinding invocation = new ProtocolBinding(
                    Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                    "query", List.of("java.lang.String"), "hessian2", List.of(), Map.of());
            OutputContract output = new OutputContract(
                    OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
            CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                    "订单查询", "按订单号查询订单状态",
                    new CapabilityManifest.Examples(List.of("查订单"), List.of(), List.of("订单")),
                    RiskLevel.READ_ONLY, Map.of("type", "object"), null,
                    invocation, output, new ResiliencePolicy(1000L, 0, 1, false));
            return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
        }
    }
}
