package com.ai.gateway.application.runtime;

import com.ai.gateway.application.catalog.CandidateResolutionService;
import com.ai.gateway.application.operation.OperationPrepareUseCase;
import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ExecutionAuditContext;
import com.ai.gateway.domain.model.ExecutionPlan;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.NlRouterMode;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.PayloadLimits;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.InteractionRepository;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import com.ai.gateway.domain.service.AliasGenerator;
import com.ai.gateway.domain.service.DeadlineBudgetManager;
import com.ai.gateway.domain.service.RedactionService;
import com.ai.gateway.domain.service.TextNormalizer;
import com.ai.gateway.domain.service.ThresholdEvaluator;
import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plane attribution of the audit trail, entry point by entry point.
 *
 * <p>Cost and failure-rate attribution is the whole point of the plane label: if the MCP,
 * Agent-host and natural-language entries all land on the same label, one entry's failures
 * are diluted by another's traffic and the exposure decision for the diagnostics plane
 * ("NL traffic stays under 1% of the runtime plane") becomes unmeasurable. These tests pin
 * the label a caller declares to the label that reaches the audit row, and pin the default
 * for callers that declare nothing.</p>
 */
class AuditPlaneTagTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Every plane an entry point can hand to the deterministic execution chain. */
    private static final List<AuditPlane> EXECUTION_PLANES = List.of(
            AuditPlane.GATEWAY_NL, AuditPlane.AGENT_HOST, AuditPlane.MCP,
            AuditPlane.A2A_INBOUND, AuditPlane.STRUCTURED);

    @TestFactory
    Stream<DynamicTest> everyEntryPlaneReachesTheSuccessTerminalAudit() {
        return EXECUTION_PLANES.stream().map(plane -> dynamicTest(plane.wireValue(), () -> {
            ExecutionFixture fixture = new ExecutionFixture();
            fixture.provider(new InvocationResult(Map.of("ok", true), "OK", null, null, Map.of()));

            var result = fixture.useCase().execute("req-" + plane.wireValue(), fixture.plan(),
                    fixture.principal(), fixture.manifest, plane);

            assertThat(result.errorCode()).isNull();
            fixture.captureTerminal("SUCCEEDED");
            // 成功终态既带平面又带快照版本：平面必须在最前，且不能挤掉既有字段。
            assertThat(fixture.details.getValue()).isEqualTo(
                    "{\"plane\":\"" + plane.wireValue() + "\",\"snapshotVersion\":3}");
            assertThat(fixture.context.getValue().plane()).isEqualTo(plane);
            assertWellFormed(fixture.details.getValue());
        }));
    }

    @TestFactory
    Stream<DynamicTest> everyEntryPlaneReachesTheFailureTerminalAudit() {
        return EXECUTION_PLANES.stream().map(plane -> dynamicTest(plane.wireValue(), () -> {
            ExecutionFixture fixture = new ExecutionFixture();
            when(fixture.invocation.invoke(any()))
                    .thenThrow(new IllegalStateException("provider secret leaked"));

            fixture.useCase().execute("req-" + plane.wireValue(), fixture.plan(),
                    fixture.principal(), fixture.manifest, plane);

            fixture.captureTerminal(ErrorCode.PROTOCOL_ERROR.name());
            assertThat(fixture.details.getValue()).isEqualTo("{\"plane\":\"" + plane.wireValue()
                    + "\",\"reason\":\"provider_invocation_failed\"}");
            // 归因原因是代码内常量，绝不能夹带 provider 原文。
            assertThat(fixture.details.getValue()).doesNotContain("provider secret leaked");
            assertWellFormed(fixture.details.getValue());
        }));
    }

    /** A caller that declares no plane is billed to the structured entry, never to a guess. */
    @Test
    void theOverloadWithoutAPlaneIsAttributedToTheStructuredEntry() throws Exception {
        ExecutionFixture fixture = new ExecutionFixture();
        when(fixture.invocation.invoke(any())).thenReturn(new InvocationResult(
                null, "FAILED", ErrorCode.PROTOCOL_ERROR, "provider detail", Map.of()));

        fixture.useCase().execute("req-legacy", fixture.plan(), fixture.principal(),
                fixture.manifest);

        fixture.captureTerminal(ErrorCode.PROTOCOL_ERROR.name());
        assertThat(fixture.context.getValue().plane()).isEqualTo(AuditPlane.STRUCTURED);
        assertThat(fixture.details.getValue())
                .isEqualTo("{\"plane\":\"structured\",\"protocolStatus\":\"FAILED\"}");
        assertWellFormed(fixture.details.getValue());
    }

    private static void assertWellFormed(String detailsJson) throws Exception {
        // 审计明细是手工拼装的，任何一处遗漏转义都会让下游解析器读到半截 JSON。
        assertThat(JSON.readTree(detailsJson).get(AuditPlane.FIELD).asText()).isNotBlank();
    }

    /** The structured boundary must pass the caller's plane through, not re-derive one. */
    @Test
    void structuredInvocationForwardsTheCallerPlane() {
        DeterministicExecutionUseCase execution = mock(DeterministicExecutionUseCase.class);
        when(execution.execute(anyString(), any(), any(), any(), any()))
                .thenReturn(new DeterministicExecutionUseCase.ExecutionResult(
                        Map.of("ok", true), null, "done"));
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        when(authorization.authorizeExecution(any(), anyString(), anyString())).thenReturn(true);
        SchemaValidator schemaValidator = mock(SchemaValidator.class);
        when(schemaValidator.validate(any(), any()))
                .thenReturn(new ValidationReport(true, List.of(), List.of()));
        StructuredInvocationUseCase useCase = new StructuredInvocationUseCase(
                mock(AuthenticationPort.class), authorization, mock(CatalogPort.class),
                schemaValidator, mock(TypeConverterRegistry.class), mock(AuditPort.class),
                execution, "test");

        useCase.invokeResolved("req-mcp", principal(), snapshot(), MANIFEST, Map.of(),
                "zh-CN", AuditPlane.MCP);
        useCase.invokeResolved("req-plain", principal(), snapshot(), MANIFEST, Map.of(), "zh-CN");

        verify(execution).execute(eq("req-mcp"), any(), any(), any(), eq(AuditPlane.MCP));
        // 未声明平面的既有调用方保持结构化归属：新增平面不改动既有调用点。
        verify(execution).execute(eq("req-plain"), any(), any(), any(),
                eq(AuditPlane.STRUCTURED));
    }

    /** MCP rides the Agent dispatcher but must not be billed to the Agent host. */
    @Test
    void agentDispatcherDefaultsToTheHostPlaneAndForwardsAnExplicitOne() {
        StructuredInvocationUseCase structured = mock(StructuredInvocationUseCase.class);
        when(structured.invokeResolved(anyString(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(new StructuredInvocationUseCase.Result(
                        StructuredInvocationUseCase.Status.COMPLETED, Map.of("ok", true), null,
                        "done", 3L, "order.query", "1.0.0"));
        AgentToolCallUseCase useCase = new AgentToolCallUseCase(
                mock(AuthenticationPort.class), mock(AuthorizationPort.class),
                mock(CatalogPort.class), structured, mock(OperationPrepareUseCase.class), "test");

        useCase.callResolved("req-host", principal(), snapshot(), MANIFEST, Map.of(),
                "zh-CN", "idem-1", true);
        useCase.callResolved("req-mcp", principal(), snapshot(), MANIFEST, Map.of(),
                "zh-CN", "idem-2", true, AuditPlane.MCP);

        verify(structured).invokeResolved(eq("req-host"), any(), any(), any(), any(),
                anyString(), eq(AuditPlane.AGENT_HOST));
        verify(structured).invokeResolved(eq("req-mcp"), any(), any(), any(), any(),
                anyString(), eq(AuditPlane.MCP));
    }

    @Test
    void runtimeNaturalLanguageTerminalsAreBilledToTheNlPlane() {
        NlFixture fixture = new NlFixture();

        fixture.queryUseCase(NlRouterMode.COMPAT)
                .execute(RequestContext.empty(), "req-nl", "查询订单状态", "zh-CN", "UTC");

        AuditEvent event = fixture.capturedEvent();
        assertThat(event.eventType()).isEqualTo("ROUTING_TERMINAL");
        assertThat(event.detailsJson()).isEqualTo(AuditPlane.GATEWAY_NL.detailsJson());
    }

    /**
     * Diagnostics spends real model quota, so it must never share the runtime label: the
     * exposure rule for the frozen router is stated in terms of runtime-plane traffic.
     */
    @Test
    void diagnosticsTerminalsAreBilledToTheDiagnosticPlane() {
        NlFixture fixture = new NlFixture();

        fixture.diagnosticsUseCase().diagnose(fixture.admin(),
                NlRouteDiagnosticsUseCase.DiagnosticsRequest.retrievalOnly("查询订单状态"));

        AuditEvent event = fixture.capturedEvent();
        assertThat(event.eventType()).isEqualTo("CATALOG_DIAGNOSTIC");
        assertThat(event.detailsJson())
                .isEqualTo(AuditPlane.GATEWAY_NL_DIAGNOSTIC.detailsJson());
        assertThat(event.detailsJson()).isNotEqualTo(AuditPlane.GATEWAY_NL.detailsJson());
    }

    private static Principal principal() {
        return new Principal("user-1", 7L, List.of("user"), List.of(), Instant.now(), "JWT");
    }

    private static CatalogSnapshot snapshot() {
        return new CatalogSnapshot(3L, "test", List.of(MANIFEST), "policy-1", "digest");
    }

    /** Read-only capability with no argument bindings: binding is not what is under test. */
    private static final CapabilityManifest MANIFEST = manifest();

    private static CapabilityManifest manifest() {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                "order.query", "1.0.0",
                new CapabilityManifest.Owner("orders", "orders@example.com"), List.of("orders"));
        ProtocolBinding invocation = new ProtocolBinding(
                Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                "query", List.of(), "hessian2", List.of(), Map.of());
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                "订单查询", "按订单号查询订单状态",
                new CapabilityManifest.Examples(List.of("查订单"), List.of(), List.of("订单")),
                RiskLevel.READ_ONLY, Map.of("type", "object"), null,
                invocation, output, new ResiliencePolicy(1_000L, 0, 1, false));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }

    /** Drives {@link DeterministicExecutionUseCase} and captures its terminal audit call. */
    private static final class ExecutionFixture {
        private final InvocationAdapter invocation = mock(InvocationAdapter.class);
        private final AuthorizationPort authorization = mock(AuthorizationPort.class);
        private final AuditPort audit = mock(AuditPort.class);
        private final CapabilityManifest manifest = mock(CapabilityManifest.class);
        private final ArgumentCaptor<ExecutionAuditContext> context =
                ArgumentCaptor.forClass(ExecutionAuditContext.class);
        private final ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);

        private ExecutionFixture() {
            when(authorization.authorizeExecution(any(), anyString(), anyString()))
                    .thenReturn(true);
        }

        /** Stubs a provider response together with the output contract that governs it. */
        private void provider(InvocationResult result) {
            CapabilityManifest.Spec spec = mock(CapabilityManifest.Spec.class);
            when(manifest.spec()).thenReturn(spec);
            when(spec.output()).thenReturn(new OutputContract(
                    OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096));
            when(invocation.invoke(any())).thenReturn(result);
        }

        private DeterministicExecutionUseCase useCase() {
            return new DeterministicExecutionUseCase(invocation,
                    mock(TypeConverterRegistry.class), new RedactionService(),
                    mock(SchemaValidator.class), authorization, audit,
                    new DeadlineBudgetManager(), PayloadLimits.defaults());
        }

        private void captureTerminal(String resultCode) {
            verify(audit).recordTerminal(context.capture(), eq(resultCode), anyLong(),
                    details.capture());
        }

        private Principal principal() {
            return AuditPlaneTagTest.principal();
        }

        private ExecutionPlan plan() {
            return new ExecutionPlan("exec-1", "principal-digest", 3L,
                    "order.query", "1.0.0", "manifest-digest", Map.of(), List.of(),
                    "policy-1", RiskLevel.READ_ONLY,
                    new ResiliencePolicy(1_000L, 0, 1, true));
        }
    }

    /**
     * Drives both natural-language surfaces against an empty candidate set.
     *
     * <p>An unresolved retrieval is the cheapest path that still produces a terminal audit
     * event, and it spends no model quota — the plane label is set by the entry point, not
     * by how far the routing pipeline got.</p>
     */
    private static final class NlFixture {
        private final AuthenticationPort authentication = mock(AuthenticationPort.class);
        private final AuthorizationPort authorization = mock(AuthorizationPort.class);
        private final CatalogPort catalog = mock(CatalogPort.class);
        private final CandidateRetriever retriever = mock(CandidateRetriever.class);
        private final AuditPort audit = mock(AuditPort.class);

        private CandidateResolutionService resolution() {
            when(authentication.authenticate(any())).thenReturn(principal());
            when(catalog.loadCurrentSnapshot("test")).thenReturn(snapshot());
            when(authorization.filterVisibleCapabilities(any(), anyList()))
                    .thenReturn(List.of(MANIFEST));
            when(retriever.retrieve(anyString(), anyList(), anyInt())).thenReturn(List.of());
            when(retriever.indexedCatalogVersion()).thenReturn(3L);
            return new CandidateResolutionService(catalog, authorization, retriever,
                    new TextNormalizer(), "test");
        }

        private NaturalLanguageQueryUseCase queryUseCase(NlRouterMode mode) {
            return new NaturalLanguageQueryUseCase(authentication, resolution(), audit,
                    new ThresholdEvaluator(), mock(InteractionRepository.class),
                    mock(SelectDecisionProcessor.class), NlRouterPolicy.of(mode));
        }

        private NlRouteDiagnosticsUseCase diagnosticsUseCase() {
            return new NlRouteDiagnosticsUseCase(resolution(),
                    new CapabilityPublicProjectionService(), new AliasGenerator(),
                    new ThresholdEvaluator(), mock(LlmRouterPort.class), audit,
                    NlRouterPolicy.of(NlRouterMode.DIAGNOSTIC));
        }

        private Principal admin() {
            return new Principal("admin-1", 7L, List.of("admin"), List.of("gateway:admin"),
                    Instant.now(), "JWT");
        }

        private AuditEvent capturedEvent() {
            ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
            verify(audit).recordEvent(event.capture());
            return event.getValue();
        }
    }
}
