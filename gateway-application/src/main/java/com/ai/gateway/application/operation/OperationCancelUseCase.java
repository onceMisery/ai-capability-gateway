package com.ai.gateway.application.operation;

import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.OperationRepository;
import com.ai.gateway.domain.service.OperationStateMachine;
import com.ai.gateway.domain.service.Sha256Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the cancel transition for prepared write operations.
 * Controllers must not mutate operation state directly.
 */
public final class OperationCancelUseCase {

    private final OperationRepository operationRepository;
    private final AuditPort auditPort;
    private final OperationStateMachine stateMachine;

    public OperationCancelUseCase(OperationRepository operationRepository,
                                  AuditPort auditPort,
                                  OperationStateMachine stateMachine) {
        this.operationRepository = Objects.requireNonNull(operationRepository);
        this.auditPort = Objects.requireNonNull(auditPort);
        this.stateMachine = Objects.requireNonNull(stateMachine);
    }

    public CancelResult cancel(String operationId, Principal principal) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(principal, "principal must not be null");

        Optional<OperationRecord> recordOptional = operationRepository.findById(operationId);
        if (recordOptional.isEmpty()) {
            auditUnbound(operationId, principal, "operation_not_found", "NOT_FOUND");
            return new CancelResult(false, "NOT_FOUND", "Operation not found");
        }

        OperationRecord record = recordOptional.get();
        if (!isOwner(record, principal)) {
            audit(record, "permission_denied", "PERMISSION_DENIED");
            return new CancelResult(false, "NOT_FOUND", "Operation not found");
        }

        if (record.state() != OperationState.PREPARED) {
            audit(record, "invalid_state", record.state().name());
            return new CancelResult(false, record.state().name(),
                    "Operation cannot be cancelled in state: " + record.state());
        }

        stateMachine.validateTransition(OperationState.PREPARED, OperationState.CANCELLED);
        boolean cancelled = operationRepository.casUpdateState(
                operationId, OperationState.PREPARED, OperationState.CANCELLED, record.version());
        if (!cancelled) {
            OperationRecord current = operationRepository.findById(operationId).orElse(record);
            audit(current, "concurrent_state_change", "CONFLICT");
            return new CancelResult(false, current.state().name(),
                    "Operation state changed concurrently");
        }

        audit(record, "cancelled", OperationState.CANCELLED.name());
        return new CancelResult(true, OperationState.CANCELLED.name(), "Operation cancelled");
    }

    static String subjectDigest(String subject) {
        return Sha256Digest.sha256Hex(subject);
    }

    private boolean isOwner(OperationRecord record, Principal principal) {
        return record.orgId() == principal.orgId()
                && record.principalDigest().equals(subjectDigest(principal.subject()));
    }

    private void auditUnbound(String operationId, Principal principal, String reason,
                              String resultCode) {
        auditPort.recordEvent(new AuditEvent(
                UUID.randomUUID().toString(), "OPERATION_CANCEL_REJECTED", Instant.now(),
                subjectDigest(principal.subject()), principal.orgId(), operationId,
                operationId, null, null, null, 0L, null, null, resultCode, 0L,
                // 没有操作记录就没有可信的发起平面来源：显式标注 unknown，不猜。
                AuditPlane.UNKNOWN.detailsJson("reason", reason)));
    }

    private void audit(OperationRecord record, String reason, String resultCode) {
        auditPort.recordEvent(new AuditEvent(
                UUID.randomUUID().toString(),
                OperationState.CANCELLED.name().equals(resultCode)
                        ? "OPERATION_CANCELLED" : "OPERATION_CANCEL_REJECTED",
                Instant.now(), record.principalDigest(), record.orgId(), record.operationId(),
                record.operationId(), record.capabilityId(), record.capabilityVersion(),
                record.manifestDigest(), record.snapshotVersion(), record.policyDecisionId(),
                // 平面取自 Prepare 阶段冻结的记录：Cancel 是独立请求，无从推断发起入口。
                null, resultCode, 0L, record.originPlane().detailsJson("reason", reason)));
    }

    public record CancelResult(boolean success, String state, String message) {
    }
}
