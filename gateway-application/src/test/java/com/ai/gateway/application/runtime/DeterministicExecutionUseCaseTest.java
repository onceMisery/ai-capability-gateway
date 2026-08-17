package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ExecutionPlan;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.PayloadLimits;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import com.ai.gateway.domain.service.DeadlineBudgetManager;
import com.ai.gateway.domain.service.RedactionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeterministicExecutionUseCaseTest {

    @Test
    void authorizationFailureReturnsStableErrorAndTerminalAudit() {
        Fixture fixture = new Fixture();
        when(fixture.authorization.authorizeExecution(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("authorization database leaked"));

        DeterministicExecutionUseCase.ExecutionResult result = fixture.useCase().execute(
                "req-auth", fixture.plan(), fixture.principal(), fixture.manifest);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED.name());
        assertThat(result.summary()).doesNotContain("authorization database leaked");
        verify(fixture.audit).recordTerminal(eq("req-auth"), eq("order.query"),
                eq("1.0.0"), eq(ErrorCode.PERMISSION_DENIED.name()), anyLong(), anyString());
        verifyNoInteractions(fixture.invocation);
    }

    @Test
    void providerExceptionDoesNotExposeProviderMessage() {
        Fixture fixture = new Fixture();
        when(fixture.authorization.authorizeExecution(any(), anyString(), anyString()))
                .thenReturn(true);
        when(fixture.invocation.invoke(any()))
                .thenThrow(new IllegalStateException("provider secret leaked"));

        DeterministicExecutionUseCase.ExecutionResult result = fixture.useCase().execute(
                "req-provider", fixture.plan(), fixture.principal(), fixture.manifest);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.PROTOCOL_ERROR.name());
        assertThat(result.summary()).doesNotContain("provider secret leaked");
        verify(fixture.audit).recordTerminal(eq("req-provider"), eq("order.query"),
                eq("1.0.0"), eq(ErrorCode.PROTOCOL_ERROR.name()), anyLong(), anyString());
    }

    @Test
    void providerErrorResultDoesNotExposeProviderMessage() {
        Fixture fixture = new Fixture();
        when(fixture.authorization.authorizeExecution(any(), anyString(), anyString()))
                .thenReturn(true);
        when(fixture.invocation.invoke(any())).thenReturn(new InvocationResult(
                null, "FAILED", ErrorCode.PROTOCOL_ERROR,
                "provider response contained a secret", Map.of()));

        DeterministicExecutionUseCase.ExecutionResult result = fixture.useCase().execute(
                "req-result", fixture.plan(), fixture.principal(), fixture.manifest);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.PROTOCOL_ERROR.name());
        assertThat(result.summary()).doesNotContain("provider response contained a secret");
        verify(fixture.audit).recordTerminal(eq("req-result"), eq("order.query"),
                eq("1.0.0"), eq(ErrorCode.PROTOCOL_ERROR.name()), anyLong(), anyString());
    }

    @Test
    void resultGovernanceFailureDoesNotExposeInternalMessage() {
        Fixture fixture = new Fixture();
        when(fixture.authorization.authorizeExecution(any(), anyString(), anyString()))
                .thenReturn(true);
        when(fixture.invocation.invoke(any())).thenReturn(new InvocationResult(
                Map.of("ok", true), "OK", null, null, Map.of()));

        DeterministicExecutionUseCase.ExecutionResult result = fixture.useCase().execute(
                "req-governance", fixture.plan(), fixture.principal(), fixture.manifest);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.PROTOCOL_ERROR.name());
        assertThat(result.summary()).doesNotContain("Cannot invoke");
        verify(fixture.audit).recordTerminal(eq("req-governance"), eq("order.query"),
                eq("1.0.0"), eq(ErrorCode.PROTOCOL_ERROR.name()), anyLong(), anyString());
    }

    @Test
    void oversizedProviderResultReturnsStablePayloadError() {
        Fixture fixture = new Fixture();
        CapabilityManifest.Spec spec = mock(CapabilityManifest.Spec.class);
        when(fixture.manifest.spec()).thenReturn(spec);
        when(spec.output()).thenReturn(new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 1024));
        when(fixture.authorization.authorizeExecution(any(), anyString(), anyString()))
                .thenReturn(true);
        when(fixture.invocation.invoke(any())).thenReturn(new InvocationResult(
                Map.of("message", "中文结果"), "OK", null, null, Map.of()));

        DeterministicExecutionUseCase.ExecutionResult result = fixture.useCase(
                new PayloadLimits(64 * 1024L, 10L, 16, 1000, 1000, 16 * 1024, 10000L))
                .execute("req-too-large", fixture.plan(), fixture.principal(), fixture.manifest);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.RESULT_TOO_LARGE.name());
        verify(fixture.audit).recordTerminal(eq("req-too-large"), eq("order.query"),
                eq("1.0.0"), eq(ErrorCode.RESULT_TOO_LARGE.name()), anyLong(), anyString());
    }

    private static final class Fixture {
        private final InvocationAdapter invocation = mock(InvocationAdapter.class);
        private final AuthorizationPort authorization = mock(AuthorizationPort.class);
        private final AuditPort audit = mock(AuditPort.class);
        private final CapabilityManifest manifest = mock(CapabilityManifest.class);

        private DeterministicExecutionUseCase useCase() {
            return useCase(PayloadLimits.defaults());
        }

        private DeterministicExecutionUseCase useCase(PayloadLimits payloadLimits) {
            return new DeterministicExecutionUseCase(invocation,
                    mock(TypeConverterRegistry.class), new RedactionService(),
                    mock(SchemaValidator.class), authorization, audit,
                    new DeadlineBudgetManager(), payloadLimits);
        }

        private Principal principal() {
            return new Principal("user-1", 7L, List.of("user"), List.of(),
                    Instant.now(), "JWT");
        }

        private ExecutionPlan plan() {
            return new ExecutionPlan("exec-1", "principal-digest", 3L,
                    "order.query", "1.0.0", "manifest-digest", Map.of(),
                    List.of(), "policy-1", RiskLevel.READ_ONLY,
                    new ResiliencePolicy(1_000L, 0, 1, true));
        }
    }
}
