package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import com.ai.gateway.domain.service.DeadlineBudgetManager;
import com.ai.gateway.domain.service.RedactionService;
import com.ai.gateway.domain.service.Sha256Digest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredInvocationUseCaseTest {

    @Test
    void invokesVisibleReadOnlyCapabilityThroughDeterministicKernel() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CatalogPort catalog = mock(CatalogPort.class);
        SchemaValidator schemaValidator = mock(SchemaValidator.class);
        InvocationAdapter invocationAdapter = mock(InvocationAdapter.class);
        com.ai.gateway.domain.port.AuditPort audit = mock(com.ai.gateway.domain.port.AuditPort.class);
        CapabilityManifest manifest = manifest(RiskLevel.READ_ONLY);
        Principal principal = new Principal("user-1", 7L, List.of("user"), List.of(),
                Instant.now(), "JWT");
        when(authentication.authenticate(any())).thenReturn(principal);
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(new CatalogSnapshot(8L, "production", List.of(manifest),
                        "policy-8", "snapshot-digest"));
        when(authorization.filterVisibleCapabilities(principal, List.of(manifest)))
                .thenReturn(List.of(manifest));
        when(authorization.authorizeExecution(principal, "orders.query", "1.0.0"))
                .thenReturn(true);
        when(schemaValidator.validate(anyMap(), anyMap())).thenReturn(ValidationReport.success());
        when(invocationAdapter.invoke(any())).thenReturn(
                new InvocationResult(Map.of("status", "ok"), "OK", null, null, Map.of()));

        DeterministicExecutionUseCase deterministic = new DeterministicExecutionUseCase(
                invocationAdapter, mock(TypeConverterRegistry.class), new RedactionService(),
                schemaValidator, authorization, audit, new DeadlineBudgetManager());
        StructuredInvocationUseCase useCase = new StructuredInvocationUseCase(
                authentication, authorization, catalog, schemaValidator,
                mock(TypeConverterRegistry.class), audit, deterministic, "production");

        StructuredInvocationUseCase.Result result = useCase.invoke(
                RequestContext.empty(), "req-structured", "orders.query", "1.0.0",
                Map.of(), "zh-CN");

        assertThat(result.status()).isEqualTo(StructuredInvocationUseCase.Status.COMPLETED);
        assertThat(result.data()).containsEntry("data", Map.of("status", "ok"));
        assertThat(result.snapshotVersion()).isEqualTo(8L);
        verify(catalog).loadCurrentSnapshot("production");
        verify(audit).recordAccepted(
                "req-structured", Sha256Digest.sha256Hex(principal.subject()), principal.orgId());
    }

    @Test
    void rejectsWriteCapabilityBeforeProviderInvocation() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CatalogPort catalog = mock(CatalogPort.class);
        CapabilityManifest manifest = manifest(RiskLevel.WRITE_LOW);
        Principal principal = new Principal("user-1", 7L, List.of("user"), List.of(),
                Instant.now(), "JWT");
        when(authentication.authenticate(any())).thenReturn(principal);
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(new CatalogSnapshot(8L, "production", List.of(manifest),
                        "policy-8", "snapshot-digest"));
        when(authorization.filterVisibleCapabilities(principal, List.of(manifest)))
                .thenReturn(List.of(manifest));
        when(authorization.authorizeExecution(any(), anyString(), anyString())).thenReturn(true);

        StructuredInvocationUseCase useCase = new StructuredInvocationUseCase(
                authentication, authorization, catalog, mock(SchemaValidator.class),
                mock(TypeConverterRegistry.class), mock(com.ai.gateway.domain.port.AuditPort.class),
                mock(DeterministicExecutionUseCase.class), "production");

        StructuredInvocationUseCase.Result result = useCase.invoke(
                RequestContext.empty(), "req-write", "orders.query", "1.0.0",
                Map.of(), "zh-CN");

        assertThat(result.status()).isEqualTo(StructuredInvocationUseCase.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.CONFIRMATION_REQUIRED.name());
    }

    private static CapabilityManifest manifest(RiskLevel risk) {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                "orders.query", "1.0.0",
                new CapabilityManifest.Owner("orders", "orders@example.com"), List.of("orders"));
        ProtocolBinding binding = new ProtocolBinding(
                Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                "query", List.of(), "hessian2", List.of(), Map.of());
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                "Order query", "Query an order",
                new CapabilityManifest.Examples(List.of("find order"), List.of(), List.of("order")),
                risk, Map.of("type", "object"), null, binding, output,
                new ResiliencePolicy(1000L, 0, 1, false));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }
}
