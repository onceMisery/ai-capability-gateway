package com.ai.gateway.application.operation;

import com.ai.gateway.domain.model.ConfirmationToken;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.ArgumentPayloadCodec;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ConfirmationTokenCodec;
import com.ai.gateway.domain.port.EncryptionPort;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.OperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;

class OperationConfirmUseCaseTest {

    private OperationRepository repository;
    private InvocationAdapter invocationAdapter;
    private AuthorizationPort authorizationPort;
    private AuditPort auditPort;
    private EncryptionPort encryptionPort;
    private ConfirmationTokenCodec confirmationTokenCodec;
    private ArgumentPayloadCodec argumentPayloadCodec;
    private CatalogPort catalogPort;
    private OperationConfirmUseCase useCase;
    private Principal principal;
    private OperationRecord record;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(OperationRepository.class);
        invocationAdapter = mock(InvocationAdapter.class);
        authorizationPort = mock(AuthorizationPort.class);
        auditPort = mock(AuditPort.class);
        encryptionPort = mock(EncryptionPort.class);
        confirmationTokenCodec = mock(ConfirmationTokenCodec.class);
        argumentPayloadCodec = mock(ArgumentPayloadCodec.class);
        catalogPort = mock(CatalogPort.class);
        useCase = new OperationConfirmUseCase(
                repository, invocationAdapter, authorizationPort, auditPort, encryptionPort,
                confirmationTokenCodec, argumentPayloadCodec, catalogPort);

        principal = new Principal("user-1", 7L, List.of("operator"), List.of(),
                Instant.now(), "JWT");
        record = new OperationRecord(
                "op-1", OperationState.PREPARED, sha256(principal.subject()), principal.orgId(),
                "order.create", "1.0.0", sha256("order.create1.0.0"), 11L,
                "ciphertext", sha256("[\"order-1\",7]"), "idem-1", "policy-1",
                null, Instant.now().plusSeconds(300), 0L);

        when(repository.findById("op-1")).thenReturn(Optional.of(record));
        when(repository.casUpdateState(any(), any(), any(), any(Long.class))).thenReturn(true);
        when(authorizationPort.authorizeExecution(principal, "order.create", "1.0.0"))
                .thenReturn(true);
        when(encryptionPort.decrypt("ciphertext")).thenReturn("[\"order-1\",7]");
        when(argumentPayloadCodec.decode("[\"order-1\",7]"))
                .thenReturn(List.of("order-1", 7));
        when(catalogPort.findCapability("order.create", "1.0.0"))
                .thenReturn(Optional.of(mock(com.ai.gateway.domain.model.CapabilityManifest.class)));
        when(invocationAdapter.invoke(any())).thenReturn(
                new InvocationResult(Map.of("ok", true), "OK", null, null, Map.of()));
    }

    @Test
    void rejectsTamperedConfirmationTokenBeforeInvocation() {
        ConfirmationToken tampered = token("tampered-token", "invalid-signature");
        when(confirmationTokenCodec.verify("tampered-token"))
                .thenThrow(new IllegalArgumentException("invalid signature"));

        OperationConfirmUseCase.ConfirmResult result =
                useCase.confirm("op-1", tampered, principal);

        assertThat(result.success()).isFalse();
        verify(invocationAdapter, never()).invoke(any());
    }

    @Test
    void invokesProviderWithFrozenArgumentsFromOperationRecord() {
        ConfirmationToken token = token("valid-token", "valid-signature");
        when(confirmationTokenCodec.verify("valid-token")).thenReturn(token);

        OperationConfirmUseCase.ConfirmResult result =
                useCase.confirm("op-1", token, principal);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<InvocationRequest> request = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(invocationAdapter).invoke(request.capture());
        assertThat(request.getValue().boundArguments()).containsExactly("order-1", 7);
    }

    @Test
    void integrityFailureAfterExecutionClaimTransitionsToFailedAndAuditsOperationId() {
        ConfirmationToken token = token("valid-token", "valid-signature");
        when(confirmationTokenCodec.verify("valid-token")).thenReturn(token);
        when(encryptionPort.decrypt("ciphertext")).thenReturn("tampered");

        OperationConfirmUseCase.ConfirmResult result =
                useCase.confirm("op-1", token, principal);

        assertThat(result.success()).isFalse();
        assertThat(result.finalState()).isEqualTo(OperationState.FAILED.name());
        verify(repository).casUpdateState("op-1", OperationState.EXECUTING,
                OperationState.FAILED, 1L);
        verify(auditPort, atLeastOnce()).recordTerminal(
                org.mockito.ArgumentMatchers.eq("op-1"),
                org.mockito.ArgumentMatchers.eq("order.create"),
                org.mockito.ArgumentMatchers.eq("1.0.0"),
                org.mockito.ArgumentMatchers.eq("ARGUMENT_VALIDATION_FAILED"),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void finalStateCasFailureReturnsUnknownInsteadOfSuccess() {
        ConfirmationToken token = token("valid-token", "valid-signature");
        when(confirmationTokenCodec.verify("valid-token")).thenReturn(token);
        when(repository.casUpdateState(any(), any(), any(), any(Long.class)))
                .thenReturn(true, false, true);

        OperationConfirmUseCase.ConfirmResult result =
                useCase.confirm("op-1", token, principal);

        assertThat(result.success()).isFalse();
        assertThat(result.finalState()).isEqualTo(OperationState.UNKNOWN.name());
        verify(repository).casUpdateState("op-1", OperationState.EXECUTING,
                OperationState.UNKNOWN, 1L);
    }

    @Test
    void startedAuditFailureStopsBeforeProviderAndClosesExecutingState() {
        ConfirmationToken token = token("valid-token", "valid-signature");
        when(confirmationTokenCodec.verify("valid-token")).thenReturn(token);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditPort).recordStarted("op-1", "order.create", "1.0.0",
                        record.manifestDigest());

        OperationConfirmUseCase.ConfirmResult result =
                useCase.confirm("op-1", token, principal);

        assertThat(result.success()).isFalse();
        assertThat(result.finalState()).isEqualTo(OperationState.FAILED.name());
        verify(invocationAdapter, never()).invoke(any());
        verify(repository).casUpdateState("op-1", OperationState.EXECUTING,
                OperationState.FAILED, 1L);
    }

    private ConfirmationToken token(String value, String signature) {
        return new ConfirmationToken(value, record.operationId(), record.principalDigest(),
                record.orgId(), record.argumentsDigest(), signature, record.expiresAt(), false);
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }
}
