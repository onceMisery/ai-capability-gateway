package com.ai.gateway.application.operation;

import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.OperationRepository;
import com.ai.gateway.domain.service.OperationStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationCancelUseCaseTest {

    private OperationRepository repository;
    private AuditPort auditPort;
    private OperationCancelUseCase useCase;
    private Principal principal;
    private OperationRecord record;

    @BeforeEach
    void setUp() {
        repository = mock(OperationRepository.class);
        auditPort = mock(AuditPort.class);
        useCase = new OperationCancelUseCase(repository, auditPort, new OperationStateMachine());
        principal = new Principal("user-1", 7L, List.of("operator"), List.of(),
                Instant.now(), "JWT");
        record = new OperationRecord(
                "op-1", OperationState.PREPARED,
                OperationCancelUseCase.subjectDigest(principal.subject()), principal.orgId(),
                "order.create", "1.0.0", "manifest-digest", 11L,
                "ciphertext", "arguments-digest", "idem-1", "policy-1", null,
                Instant.now().plusSeconds(300), 0L);
        when(repository.findById("op-1")).thenReturn(Optional.of(record));
        when(repository.casUpdateState("op-1", OperationState.PREPARED,
                OperationState.CANCELLED, 0L)).thenReturn(true);
    }

    @Test
    void cancelsPreparedOperationThroughStateMachineAndAuditsIt() {
        OperationCancelUseCase.CancelResult result = useCase.cancel("op-1", principal);

        assertThat(result.success()).isTrue();
        assertThat(result.state()).isEqualTo(OperationState.CANCELLED.name());
        verify(repository).casUpdateState("op-1", OperationState.PREPARED,
                OperationState.CANCELLED, 0L);
        verify(auditPort).recordEvent(any());
    }

    @Test
    void rejectsCancelForAnotherPrincipalWithoutChangingState() {
        Principal other = new Principal("other", 7L, List.of("operator"), List.of(),
                Instant.now(), "JWT");

        OperationCancelUseCase.CancelResult result = useCase.cancel("op-1", other);

        assertThat(result.success()).isFalse();
        assertThat(result.state()).isEqualTo("NOT_FOUND");
        verify(repository, never()).casUpdateState(any(), any(), any(), any(Long.class));
        verify(auditPort).recordEvent(any());
    }

    @Test
    void rejectsCancelFromTerminalState() {
        OperationRecord terminal = new OperationRecord(
                record.operationId(), OperationState.SUCCEEDED, record.principalDigest(),
                record.orgId(), record.capabilityId(), record.capabilityVersion(),
                record.manifestDigest(), record.snapshotVersion(), record.encryptedArguments(),
                record.argumentsDigest(), record.idempotencyKey(), record.policyDecisionId(),
                record.confirmationSummary(), record.expiresAt(), record.version());
        when(repository.findById("op-1")).thenReturn(Optional.of(terminal));

        OperationCancelUseCase.CancelResult result = useCase.cancel("op-1", principal);

        assertThat(result.success()).isFalse();
        assertThat(result.state()).isEqualTo(OperationState.SUCCEEDED.name());
        verify(repository, never()).casUpdateState(any(), any(), any(), any(Long.class));
    }
}
