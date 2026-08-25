package com.ai.gateway.application.runtime;

import com.ai.gateway.application.operation.OperationPrepareUseCase;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ConfirmationToken;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolCallUseCaseTest {

    @Test
    void delegatesVisibleReadToStructuredInvocation() {
        Fixtures fixtures = new Fixtures(RiskLevel.READ_ONLY);
        when(fixtures.structured.invokeResolved(anyString(), any(), any(), any(),
                anyMap(), anyString(), eq(AuditPlane.AGENT_HOST)))
                .thenReturn(new StructuredInvocationUseCase.Result(
                StructuredInvocationUseCase.Status.COMPLETED, Map.of("ok", true), null,
                "completed", 8L, "orders.query", "1.0.0"));

        AgentToolCallUseCase.Result result = fixtures.useCase().call(
                RequestContext.empty(), "req-1", "orders.query", "1.0.0",
                Map.of(), "zh-CN", 8L, "agent-call-1");

        assertThat(result.status()).isEqualTo(AgentToolCallUseCase.Status.COMPLETED);
        assertThat(result.data()).containsEntry("ok", true);
        verify(fixtures.structured).invokeResolved(anyString(), any(), any(), any(),
                anyMap(), anyString(), eq(AuditPlane.AGENT_HOST));
        verify(fixtures.prepare, never()).prepareResolved(anyString(), any(), any(), any(),
                anyMap(), anyString(), anyString());
    }

    @Test
    void delegatesLowRiskWriteToStructuredPrepare() {
        Fixtures fixtures = new Fixtures(RiskLevel.WRITE_LOW);
        ConfirmationToken token = new ConfirmationToken("token", "operation", "principal",
                7L, "arguments", "signature", Instant.now().plusSeconds(300), false);
        when(fixtures.prepare.prepareResolved(anyString(), any(), any(), any(),
                anyMap(), anyString(), anyString()))
                .thenReturn(new OperationPrepareUseCase.PrepareResult(
                        true, "operation", token, "confirm", token.expiresAt(), null));

        AgentToolCallUseCase.Result result = fixtures.useCase().call(
                RequestContext.empty(), "req-1", "orders.update", "1.0.0",
                Map.of(), "zh-CN", 8L, "agent-call-1");

        assertThat(result.status()).isEqualTo(AgentToolCallUseCase.Status.CONFIRMATION_REQUIRED);
        assertThat(result.operationId()).isEqualTo("operation");
        assertThat(result.token()).isSameAs(token);
        verify(fixtures.structured, never()).invokeResolved(anyString(), any(), any(), any(),
                anyMap(), anyString(), any(AuditPlane.class));
    }

    @Test
    void readOnlyPolicyRejectsLowRiskWriteBeforePrepare() {
        Fixtures fixtures = new Fixtures(RiskLevel.WRITE_LOW);

        AgentToolCallUseCase.Result result = fixtures.useCase().callResolved(
                "req-1", fixtures.principal, fixtures.snapshot(), fixtures.manifest,
                Map.of(), "zh-CN", "agent-call-1", false);

        assertThat(result.status()).isEqualTo(AgentToolCallUseCase.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("MCP_WRITE_DISABLED");
        verify(fixtures.prepare, never()).prepareResolved(anyString(), any(), any(), any(),
                anyMap(), anyString(), anyString());
    }

    @Test
    void rejectsHighRiskWriteBeforeEitherExecutionPath() {
        Fixtures fixtures = new Fixtures(RiskLevel.WRITE_HIGH);

        AgentToolCallUseCase.Result result = fixtures.useCase().call(
                RequestContext.empty(), "req-1", "orders.delete", "1.0.0",
                Map.of(), "zh-CN", 8L, "agent-call-1");

        assertThat(result.status()).isEqualTo(AgentToolCallUseCase.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("HIGH_RISK_WRITE_BLOCKED");
        verify(fixtures.structured, never()).invokeResolved(anyString(), any(), any(), any(),
                anyMap(), anyString(), any(AuditPlane.class));
        verify(fixtures.prepare, never()).prepareResolved(anyString(), any(), any(), any(),
                anyMap(), anyString(), anyString());
    }

    @Test
    void deniesCapabilityWhenVisibilityAuthorizationReturnsEmpty() {
        Fixtures fixtures = new Fixtures(RiskLevel.READ_ONLY);
        when(fixtures.authorization.filterVisibleCapabilities(fixtures.principal,
                List.of(fixtures.manifest))).thenReturn(List.of());

        AgentToolCallUseCase.Result result = fixtures.useCase().call(
                RequestContext.empty(), "req-1", "orders.query", "1.0.0",
                Map.of(), "zh-CN", 8L, "agent-call-1");

        assertThat(result.status()).isEqualTo(AgentToolCallUseCase.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("PERMISSION_DENIED");
        verify(fixtures.structured, never()).invokeResolved(anyString(), any(), any(), any(),
                anyMap(), anyString(), any(AuditPlane.class));
    }

    private static final class Fixtures {
        private final AuthenticationPort authentication = mock(AuthenticationPort.class);
        private final AuthorizationPort authorization = mock(AuthorizationPort.class);
        private final CatalogPort catalog = mock(CatalogPort.class);
        private final StructuredInvocationUseCase structured = mock(StructuredInvocationUseCase.class);
        private final OperationPrepareUseCase prepare = mock(OperationPrepareUseCase.class);
        private final Principal principal = new Principal("user-1", 7L, List.of("user"),
                List.of(), Instant.now(), "JWT");
        private final CapabilityManifest manifest;

        private Fixtures(RiskLevel risk) {
            manifest = manifest(risk);
            when(authentication.authenticate(any())).thenReturn(principal);
            when(catalog.loadCurrentSnapshot("production"))
                    .thenReturn(new CatalogSnapshot(8L, "production", List.of(manifest),
                            "policy-8", "digest"));
            when(authorization.filterVisibleCapabilities(principal, List.of(manifest)))
                    .thenReturn(List.of(manifest));
        }

        private AgentToolCallUseCase useCase() {
            return new AgentToolCallUseCase(authentication, authorization, catalog,
                    structured, prepare, "production");
        }

        private CatalogSnapshot snapshot() {
            return catalog.loadCurrentSnapshot("production");
        }

        private static CapabilityManifest manifest(RiskLevel risk) {
            String id = switch (risk) {
                case READ_ONLY -> "orders.query";
                case WRITE_LOW -> "orders.update";
                case WRITE_HIGH -> "orders.delete";
            };
            CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                    id, "1.0.0", new CapabilityManifest.Owner("orders", "orders@example.com"),
                    List.of());
            ProtocolBinding binding = new ProtocolBinding(
                    Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                    "run", List.of(), "hessian2", List.of(), Map.of());
            OutputContract output = new OutputContract(
                    OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
            return new CapabilityManifest("gateway.ai/v1", "Capability", metadata,
                    new CapabilityManifest.Spec("Order operation", "Order operation",
                            new CapabilityManifest.Examples(List.of(), List.of(), List.of()),
                            risk, Map.of("type", "object"), null, binding, output,
                            new ResiliencePolicy(1000L, 0, 1, false)));
        }
    }
}
