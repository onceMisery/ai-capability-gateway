package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ExecutionPlan;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.PayloadLimits;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import com.ai.gateway.domain.service.AliasGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelectDecisionProcessorTest {

    @Test
    void bindsRequestedLocaleToSystemArgument() {
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        LlmRouterPort llm = mock(LlmRouterPort.class);
        SchemaValidator schemaValidator = mock(SchemaValidator.class);
        AliasGenerator aliases = mock(AliasGenerator.class);
        TypeConverterRegistry converters = mock(TypeConverterRegistry.class);
        DeterministicExecutionUseCase execution = mock(DeterministicExecutionUseCase.class);
        CapabilityManifest manifest = manifestWithLocaleArgument();
        Principal principal = new Principal("user-1", 7L, List.of("user"), List.of(),
                Instant.now(), "JWT");

        when(aliases.generate(3L, "order.query", "1.0.0")).thenReturn("cap_order");
        when(llm.route(anyString(), any()))
                .thenReturn(new com.ai.gateway.domain.model.ModelDecision.SelectDecision(
                        "cap_order", Map.of()));
        when(schemaValidator.validate(anyMap(), anyMap())).thenReturn(ValidationReport.success());
        when(authorization.authorizeExecution(principal, "order.query", "1.0.0"))
                .thenReturn(true);
        when(execution.execute(anyString(), any(ExecutionPlan.class), eq(principal), eq(manifest),
                eq(AuditPlane.GATEWAY_NL)))
                .thenReturn(new DeterministicExecutionUseCase.ExecutionResult(
                        Map.of("ok", true), null, "ok"));

        SelectDecisionProcessor processor = new DefaultSelectDecisionProcessor(
                authorization, llm, schemaValidator, aliases, converters, execution,
                PayloadLimits.defaults());

        processor.process(
                new CandidateRetriever.ScoredCapability(manifest, 2.0),
                principal, 3L, "req-1", "查询订单", "en-US",
                System.currentTimeMillis(), (result, ignoredPrincipal, ignoredRequestId,
                                               ignoredSnapshotVersion, ignoredManifest,
                                               ignoredStartTime) -> result);

        ArgumentCaptor<ExecutionPlan> plan = ArgumentCaptor.forClass(ExecutionPlan.class);
        verify(execution).execute(eq("req-1"), plan.capture(), eq(principal), eq(manifest),
                eq(AuditPlane.GATEWAY_NL));
        assertThat(plan.getValue().resolvedProtocolArguments()).containsExactly("en-US");
    }

    private static CapabilityManifest manifestWithLocaleArgument() {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                "order.query", "1.0.0",
                new CapabilityManifest.Owner("orders", "orders@example.com"),
                List.of("orders", "read"));
        ProtocolBinding invocation = new ProtocolBinding(
                Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                "query", List.of("java.lang.String"), "hessian2",
                List.of(new ArgumentBinding(
                        0, "locale", "java.lang.String", ArgumentSource.SYSTEM,
                        "/locale", null, null, null)),
                Map.of());
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                "Order query", "Query an order",
                new CapabilityManifest.Examples(
                        List.of("find order"), List.of(), List.of("order")),
                RiskLevel.READ_ONLY, Map.of("type", "object"), null,
                invocation, output, new ResiliencePolicy(1000L, 0, 1, false));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }
}
