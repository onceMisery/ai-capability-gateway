package com.ai.gateway.application.agent;

import com.ai.gateway.application.operation.OperationCancelUseCase;
import com.ai.gateway.application.operation.OperationConfirmUseCase;
import com.ai.gateway.application.operation.OperationStatusUseCase;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AgentHostConnectorTest {

    @Test
    void storesTurnReferencesAndRejectsReferenceFromAnotherTurn() {
        Fixtures fixtures = new Fixtures();
        AgentHostConnector.ResolveResult resolved = fixtures.connector.resolve(
                RequestContext.empty(), "turn-1", "resolve-1", "query order", 5);

        assertThat(resolved.state().allows("ref-1")).isTrue();
        AgentHostConnector.CallResult rejected = fixtures.connector.call(
                RequestContext.empty(), "turn-1", "call-1", "ref-other", Map.of(),
                "zh-CN", "idem-1");

        assertThat(rejected.result().status())
                .isEqualTo(AgentModelResultMapper.ModelResult.Status.ERROR);
        assertThat(rejected.result().errorCode()).isEqualTo("TOOL_REF_NOT_IN_TURN");
        verify(fixtures.callUseCase, never()).call(
                any(Principal.class), anyString(), anyString(), anyMap(),
                anyString(), anyString());
    }

    @Test
    void mapsWriteResultToPrivateConfirmationStateAndAllowsOnlyUiConfirmation() {
        Fixtures fixtures = new Fixtures();
        fixtures.connector.resolve(RequestContext.empty(), "turn-1", "resolve-1", "query order", 5);
        Instant expiresAt = Instant.now().plusSeconds(60);
        when(fixtures.callUseCase.call(any(Principal.class), anyString(), anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(new AgentHostToolCallUseCase.Result(
                        AgentHostToolCallUseCase.Status.CONFIRMATION_REQUIRED, null, null,
                        "Update order", 8L, 42L, "op-1",
                        "private-token", true, expiresAt));
        when(fixtures.confirmUseCase.confirm(
                anyString(), anyString(), any(Principal.class)))
                .thenReturn(new OperationConfirmUseCase.ConfirmResult(
                        true, "SUCCEEDED", "completed"));

        AgentHostConnector.CallResult call = fixtures.connector.call(
                RequestContext.empty(), "turn-1", "call-1", "ref-1", Map.of("id", "1"),
                "zh-CN", "idem-1");

        assertThat(call.result().toString()).doesNotContain("private-token");
        assertThat(call.confirmationTokenHostOnly()).isEqualTo("private-token");
        assertThat(fixtures.confirmationStore.find(
                "op-1", fixtures.principalDigest()).orElseThrow().status())
                .isEqualTo(PendingConfirmationState.Status.PENDING);
        AgentHostConnector.ConfirmationResult confirmed = fixtures.connector.confirm(
                new AgentHostConnector.UserConfirmationEvent(RequestContext.empty(), "op-1"));

        assertThat(confirmed.success()).isTrue();
        assertThat(fixtures.confirmationStore.find(
                "op-1", fixtures.principalDigest())).isEmpty();
        verify(fixtures.confirmUseCase).confirm("op-1", "private-token", fixtures.principal);
    }

    @Test
    void cancellationUsesTheSamePrivatePendingStateAndCannotBeTriggeredByModelResult() {
        Fixtures fixtures = new Fixtures();
        fixtures.connector.resolve(RequestContext.empty(), "turn-1", "resolve-1", "query order", 5);
        Instant expiresAt = Instant.now().plusSeconds(60);
        when(fixtures.callUseCase.call(any(Principal.class), anyString(), anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(new AgentHostToolCallUseCase.Result(
                        AgentHostToolCallUseCase.Status.CONFIRMATION_REQUIRED, null, null,
                        "Update order", 8L, 42L, "op-cancel", "private-token", true, expiresAt));
        when(fixtures.cancelUseCase.cancel("op-cancel", fixtures.principal))
                .thenReturn(new OperationCancelUseCase.CancelResult(true, "CANCELLED", "cancelled"));

        fixtures.connector.call(RequestContext.empty(), "turn-1", "call-1", "ref-1", Map.of(),
                "zh-CN", "idem-1");
        AgentHostConnector.CancellationResult cancelled = fixtures.connector.cancel(
                new AgentHostConnector.UserCancellationEvent(RequestContext.empty(), "op-cancel"));

        assertThat(cancelled.success()).isTrue();
        assertThat(fixtures.confirmationStore.find(
                "op-cancel", fixtures.principalDigest())).isEmpty();
        verify(fixtures.cancelUseCase).cancel("op-cancel", fixtures.principal);
    }

    @Test
    void statusReadsCanonicalOperationStateAndHidesAnotherPrincipalOperation() {
        Fixtures fixtures = new Fixtures();
        OperationRecord record = operation("op-status", fixtures.principal, OperationState.UNKNOWN);
        when(fixtures.statusUseCase.query("op-status")).thenReturn(record);

        AgentHostConnector.OperationStatusResult result = fixtures.connector.status(
                RequestContext.empty(), "op-status");

        assertThat(result.found()).isTrue();
        assertThat(result.state()).isEqualTo("UNKNOWN");
        assertThat(result.errorCode()).isNull();

        when(fixtures.authentication.authenticate(any())).thenReturn(new Principal(
                "other-user", fixtures.principal.orgId(), List.of("user"), List.of(),
                Instant.now(), "test"));
        assertThat(fixtures.connector.status(RequestContext.empty(), "op-status").errorCode())
                .isEqualTo("OPERATION_NOT_FOUND");
    }

    @Test
    void permitsOnlyOneArgumentRepairPerTurn() {
        Fixtures fixtures = new Fixtures();
        fixtures.connector.resolve(RequestContext.empty(), "turn-1", "resolve-1", "query order", 5);

        assertThat(fixtures.connector.recordArgumentRepair(
                RequestContext.empty(), "turn-1").argumentRepairCount()).isEqualTo(1);
        assertThatThrownBy(() -> fixtures.connector.recordArgumentRepair(
                RequestContext.empty(), "turn-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void authenticatesOnlyOncePerResolveAndCallRequest() {
        Fixtures fixtures = new Fixtures();
        fixtures.connector.resolve(RequestContext.empty(), "turn-1", "resolve-1", "query order", 5);
        when(fixtures.callUseCase.call(any(Principal.class), anyString(), anyString(), anyMap(),
                anyString(), anyString())).thenReturn(new AgentHostToolCallUseCase.Result(
                        AgentHostToolCallUseCase.Status.COMPLETED, Map.of("ok", true), null,
                        "completed", 8L, 42L, null, null, false, null));

        fixtures.connector.call(RequestContext.empty(), "turn-1", "call-1", "ref-1",
                Map.of(), "zh-CN", "idem-1");

        verify(fixtures.authentication, times(2)).authenticate(any());
    }

    @Test
    void rejectsSameSubjectTurnWhenOrganizationContextChanges() {
        Fixtures fixtures = new Fixtures();
        Principal anotherOrganization = new Principal(
                fixtures.principal.subject(), 8L, fixtures.principal.roles(),
                fixtures.principal.permissions(), Instant.now(), "test");
        when(fixtures.authentication.authenticate(any()))
                .thenReturn(fixtures.principal, anotherOrganization);

        fixtures.connector.resolve(
                RequestContext.empty(), "turn-1", "resolve-1", "query order", 5);
        AgentHostConnector.CallResult result = fixtures.connector.call(
                RequestContext.empty(), "turn-1", "call-1", "ref-1", Map.of(),
                "zh-CN", "idem-1");

        assertThat(result.result().errorCode()).isEqualTo("TOOL_REF_NOT_IN_TURN");
        verify(fixtures.callUseCase, never()).call(
                any(Principal.class), anyString(), anyString(), anyMap(),
                anyString(), anyString());
    }

    private static final class Fixtures {
        private final AuthenticationPort authentication = mock(AuthenticationPort.class);
        private final AgentCapabilityResolver resolver = mock(AgentCapabilityResolver.class);
        private final AgentHostToolCallUseCase callUseCase = mock(AgentHostToolCallUseCase.class);
        private final PendingConfirmationStore confirmationStore =
                new InMemoryPendingConfirmationStore(10);
        private final AgentTurnStore turnStore = new InMemoryAgentTurnStore(10);
        private final OperationConfirmUseCase confirmUseCase = mock(OperationConfirmUseCase.class);
        private final OperationCancelUseCase cancelUseCase = mock(OperationCancelUseCase.class);
        private final OperationStatusUseCase statusUseCase = mock(OperationStatusUseCase.class);
        private final TelemetryPort telemetry = mock(TelemetryPort.class);
        private final Principal principal = new Principal(
                "user-1", 7L, List.of("user"), List.of(),
                Instant.now(), "test");
        private final AgentHostConnector connector;

        private Fixtures() {
            when(authentication.authenticate(any())).thenReturn(principal);
            when(resolver.resolve(any(Principal.class), anyString(),
                    org.mockito.ArgumentMatchers.eq(5)))
                    .thenReturn(new AgentCapabilityResolver.Resolution(
                            AgentCapabilityResolver.Status.RESOLVED, null, 8L, 42L,
                            List.of(new AgentCapabilityResolver.Candidate(
                                    "ref-1", "Query", "Query order",
                                    CapabilityPublicProjectionService.SchemaClass.SIMPLE,
                                    Map.of("required", List.of("id")), "DIRECT")),
                            null, Instant.now().plusSeconds(60)));
            connector = new AgentHostConnector(
                    authentication, resolver, callUseCase,
                    new AgentModelResultMapper(confirmationStore), turnStore,
                    confirmationStore, confirmUseCase, cancelUseCase, statusUseCase,
                    telemetry, new AgentResolveAdmissionController(10, telemetry));
        }

        private String principalDigest() {
            return com.ai.gateway.domain.service.PrincipalFingerprint.digest(principal);
        }
    }

    private static OperationRecord operation(
            String operationId, Principal principal, OperationState state) {
        return new OperationRecord(operationId, state,
                com.ai.gateway.domain.service.Sha256Digest.sha256Hex(principal.subject()),
                principal.orgId(), "capability", "1.0.0", "manifest", 8L,
                "encrypted", "arguments", "idempotency", "policy",
                null,
                Instant.now().plusSeconds(60), 1L);
    }
}
