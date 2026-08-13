package com.ai.gateway.application.operation;

import com.ai.gateway.domain.model.ConfirmationToken;
import com.ai.gateway.domain.model.DeadlineBudget;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.SystemContext;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.ArgumentPayloadCodec;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ConfirmationTokenCodec;
import com.ai.gateway.domain.port.EncryptionPort;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.OperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case for the Confirm phase of the write-operation two-phase protocol
 *
 * <p>The Confirm phase:</p>
 * <ol>
 * <li>Verify token signature, expiry, and single-use status.</li>
 * <li>Verify the current Principal matches the Prepare-phase Principal.</li>
 * <li>Verify the operation is still in PREPARED state.</li>
 * <li>Verify the capability has not been suspended and the manifest digest
 * has not been revoked.</li>
 * <li>Re-execute authorization with the frozen parameters.</li>
 * <li>CAS (Compare-And-Swap) atomic acquire of execution right.</li>
 * <li>Invoke the original capability using the original protocol binding.</li>
 * <li>Persist the final state and return the operationId.</li>
 * </ol>
 *
 * <p>Duplicate confirm calls return the operation's current status without
 * creating a new operation. The CAS on the optimistic
 * concurrency version prevents duplicate execution when multiple confirm
 * attempts race.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable per-request state.</p>
 *
 * @see OperationRepository
 * @see InvocationAdapter
 * @since 0.1.0
 */
public final class OperationConfirmUseCase {

    private static final Logger log = LoggerFactory.getLogger(OperationConfirmUseCase.class);

    private final OperationRepository operationRepository;
    private final InvocationAdapter invocationAdapter;
    private final AuthorizationPort authorizationPort;
    private final AuditPort auditPort;
    private final EncryptionPort encryptionPort;
    private final ConfirmationTokenCodec confirmationTokenCodec;
    private final ArgumentPayloadCodec argumentPayloadCodec;
    private final CatalogPort catalogPort;

    /**
     * Constructs a new OperationConfirmUseCase with the required dependencies.
     *
     * @param operationRepository the repository for loading and updating operation records
     * @param invocationAdapter the protocol invocation adapter
     * @param authorizationPort the authorization port for re-authorization
     * @param auditPort the audit port for event recording
     * @param encryptionPort the encryption port for decrypting frozen parameters
     * @throws NullPointerException if any argument is null
     */
    public OperationConfirmUseCase(OperationRepository operationRepository,
                                    InvocationAdapter invocationAdapter,
                                    AuthorizationPort authorizationPort,
                                    AuditPort auditPort,
                                    EncryptionPort encryptionPort,
                                    ConfirmationTokenCodec confirmationTokenCodec,
                                    ArgumentPayloadCodec argumentPayloadCodec,
                                    CatalogPort catalogPort) {
        this.operationRepository = Objects.requireNonNull(operationRepository,
                "operationRepository must not be null");
        this.invocationAdapter = Objects.requireNonNull(invocationAdapter,
                "invocationAdapter must not be null");
        this.authorizationPort = Objects.requireNonNull(authorizationPort,
                "authorizationPort must not be null");
        this.auditPort = Objects.requireNonNull(auditPort,
                "auditPort must not be null");
        this.encryptionPort = Objects.requireNonNull(encryptionPort,
                "encryptionPort must not be null");
        this.confirmationTokenCodec = Objects.requireNonNull(confirmationTokenCodec,
                "confirmationTokenCodec must not be null");
        this.argumentPayloadCodec = Objects.requireNonNull(argumentPayloadCodec,
                "argumentPayloadCodec must not be null");
        this.catalogPort = Objects.requireNonNull(catalogPort,
                "catalogPort must not be null");
    }

    /**
     * Confirms and executes a prepared write operation.
     *
     * <p>Duplicate confirm calls return the operation's current status without
     * creating a new operation.</p>
     *
     * @param operationId the operation identifier from the Prepare phase
     * @param token the confirmation token from the Prepare phase
     * @param principal the current authenticated principal
     * @return the confirm result
     * @throws NullPointerException if any argument is null
     */
    public ConfirmResult confirm(String operationId,
                                  ConfirmationToken token,
                                  Principal principal) {
        Objects.requireNonNull(token, "token must not be null");
        return confirm(operationId, token.token(), principal);
    }

    /**
     * Confirms an operation using only the opaque token supplied by the client.
     */
    public ConfirmResult confirm(String operationId,
                                  String tokenValue,
                                  Principal principal) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(tokenValue, "tokenValue must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        log.info("Confirm phase started: operationId={}", operationId);

        ConfirmationToken verifiedToken;
        try {
            verifiedToken = confirmationTokenCodec.verify(tokenValue);
        } catch (RuntimeException e) {
            recordUnboundRejection(operationId, principal,
                    ErrorCode.ARGUMENT_VALIDATION_FAILED, "invalid_token");
            return new ConfirmResult(false, null, "Invalid confirmation token");
        }

        // Load the operation record
        Optional<OperationRecord> recordOpt = operationRepository.findById(operationId);
        if (recordOpt.isEmpty()) {
            recordUnboundRejection(operationId, principal,
                    ErrorCode.CAPABILITY_UNAVAILABLE, "operation_not_found");
            return new ConfirmResult(false, null, "Operation not found: " + operationId);
        }

        OperationRecord record = recordOpt.get();

        // Handle duplicate confirm: return current status without creating new operation
        if (record.state() != OperationState.PREPARED) {
            log.info("Duplicate confirm for operation {}: current state={}",
                    operationId, record.state());
            recordRejected(record, "CONFIRM_REPLAY", null, "duplicate_confirm");
            return new ConfirmResult(true, record.state().name(),
                    "Operation already processed: " + record.state());
        }

        // Step 1: Verify token signature, expiry, and single-use status
        if (verifiedToken.expiresAt().isBefore(Instant.now())
                || !verifiedToken.expiresAt().equals(record.expiresAt())) {
            log.warn("Token expired for operation {}", operationId);
            // Transition to EXPIRED
            operationRepository.casUpdateState(operationId,
                    OperationState.PREPARED, OperationState.EXPIRED, record.version());
            recordRejected(record, "CONFIRM_EXPIRED", ErrorCode.ARGUMENT_VALIDATION_FAILED,
                    "token_expired");
            return new ConfirmResult(false, OperationState.EXPIRED.name(),
                    "Confirmation token has expired");
        }
        // Verify token is bound to this operation
        if (!verifiedToken.operationId().equals(operationId)
                || !verifiedToken.principalDigest().equals(record.principalDigest())
                || verifiedToken.orgId() != record.orgId()
                || !verifiedToken.argumentsDigest().equals(record.argumentsDigest())) {
            log.warn("Token operationId mismatch: expected={}, got={}",
                    operationId, verifiedToken.operationId());
            recordRejected(record, "CONFIRM_REJECTED", ErrorCode.ARGUMENT_VALIDATION_FAILED,
                    "token_binding_mismatch");
            return new ConfirmResult(false, null,
                    "Token is not bound to this operation");
        }

        // Step 2: Verify current Principal matches Prepare-phase Principal
        String currentPrincipalDigest = computeDigest(principal.subject());
        if (!currentPrincipalDigest.equals(record.principalDigest())) {
            log.warn("Principal mismatch for operation {}", operationId);
            recordRejected(record, "CONFIRM_REJECTED", ErrorCode.PERMISSION_DENIED,
                    "principal_mismatch");
            return new ConfirmResult(false, null,
                    "Current Principal does not match Prepare-phase Principal");
        }
        // Verify orgId matches
        if (principal.orgId() != record.orgId()) {
            log.warn("OrgId mismatch for operation {}", operationId);
            recordRejected(record, "CONFIRM_REJECTED", ErrorCode.PERMISSION_DENIED,
                    "organization_mismatch");
            return new ConfirmResult(false, null,
                    "Current orgId does not match Prepare-phase orgId");
        }

        // Step 3: Verify operation is still PREPARED (already checked above)
        // This is re-verified by the CAS in step 6.

        // Step 4: a suspended or revoked capability is absent from the current catalog.
        if (catalogPort.findCapability(record.capabilityId(), record.capabilityVersion()).isEmpty()) {
            recordRejected(record, "CONFIRM_REJECTED", ErrorCode.CAPABILITY_UNAVAILABLE,
                    "capability_inactive");
            return new ConfirmResult(false, record.state().name(),
                    "Capability is no longer active");
        }

        // Step 5: Re-execute authorization with frozen parameters
        boolean authorized = authorizationPort.authorizeExecution(
                principal, record.capabilityId(), record.capabilityVersion());
        if (!authorized) {
            log.warn("Re-authorization denied for operation {}", operationId);
            auditPort.recordEvent(new com.ai.gateway.domain.model.AuditEvent(
                    UUID.randomUUID().toString(),
                    "CONFIRM_AUTHORIZATION_DENIED",
                    Instant.now(),
                    record.principalDigest(),
                    record.orgId(),
                    operationId, operationId,
                    record.capabilityId(), record.capabilityVersion(),
                    record.manifestDigest(), record.snapshotVersion(),
                    null, null, "AUTHORIZATION_DENIED", 0L,
                    "{\"reason\":\"authorization_denied\"}"));
            return new ConfirmResult(false, null,
                    "Re-authorization denied");
        }

        // Step 6: CAS atomic acquire execution right (PREPARED -> EXECUTING)
        boolean casSuccess = operationRepository.casUpdateState(
                operationId,
                OperationState.PREPARED,
                OperationState.EXECUTING,
                record.version()
        );
        if (!casSuccess) {
            log.warn("CAS failed for operation {}: another confirm may have raced",
                    operationId);
            // Reload and return current state
            OperationRecord current = operationRepository.findById(operationId).orElse(record);
            recordRejected(current, "CONFIRM_REPLAY", null, "concurrent_confirm");
            return new ConfirmResult(true, current.state().name(),
                    "Operation is being processed by another request: " + current.state());
        }

        // Record STARTED audit event
        try {
            auditPort.recordStarted(operationId,
                    record.capabilityId(), record.capabilityVersion(),
                    record.manifestDigest());
        } catch (RuntimeException e) {
            OperationState persisted = persistExecutionFailure(record);
            return new ConfirmResult(false, persisted.name(),
                    "Audit persistence failed before provider invocation");
        }

        // Step 7: Invoke original capability using original protocol binding
        // Decrypt the frozen arguments
        List<Object> boundArguments;
        try {
            String decryptedArgs = encryptionPort.decrypt(record.encryptedArguments());
            if (!computeDigest(decryptedArgs).equals(record.argumentsDigest())) {
                throw new IllegalArgumentException("frozen argument digest mismatch");
            }
            boundArguments = argumentPayloadCodec.decode(decryptedArgs);
        } catch (RuntimeException e) {
            OperationState persisted = persistExecutionFailure(record);
            auditPort.recordTerminal(operationId,
                    record.capabilityId(), record.capabilityVersion(),
                    persisted == OperationState.UNKNOWN
                            ? ErrorCode.EXECUTION_UNKNOWN.name()
                            : ErrorCode.ARGUMENT_VALIDATION_FAILED.name(), 0L,
                    "{\"reason\":\"frozen_arguments_invalid\"}");
            return new ConfirmResult(false, persisted.name(),
                    "Frozen operation arguments failed integrity verification");
        }

        // Construct the invocation request with the frozen parameters
        // In a full implementation, the decrypted arguments would be deserialized
        // back to the ordered list. For the skeleton, we create a placeholder.
        SystemContext systemContext = new SystemContext(
                operationId,
                Instant.now().toEpochMilli() + 30000,
                record.idempotencyKey(),
                "zh-CN"
        );

        InvocationRequest invocationRequest = new InvocationRequest(
                record.capabilityId(),
                record.capabilityVersion(),
                record.manifestDigest(),
                new DeadlineBudget(30000, 30000),
                record.idempotencyKey(),
                systemContext,
                boundArguments
        );

        InvocationResult invocationResult;
        try {
            invocationResult = invocationAdapter.invoke(invocationRequest);
        } catch (Exception e) {
            log.error("Invocation failed for operation {}: {}", operationId, e.getMessage());
            // Transition to FAILED
            OperationState persisted = persistExecutionFailure(record);
            auditPort.recordTerminal(operationId,
                    record.capabilityId(), record.capabilityVersion(),
                    persisted == OperationState.UNKNOWN
                            ? ErrorCode.EXECUTION_UNKNOWN.name()
                            : ErrorCode.PROTOCOL_ERROR.name(), 0,
                    "{\"reason\":\"provider_invocation_failed\"}");
            return new ConfirmResult(false, persisted.name(),
                    "Provider invocation failed");
        }

        // Step 8: Persist final state and return operationId
        OperationState finalState = invocationResult.errorCode() == null
                ? OperationState.SUCCEEDED
                : OperationState.FAILED;

        boolean finalStatePersisted = operationRepository.casUpdateState(operationId,
                OperationState.EXECUTING, finalState,
                record.version() + 1);

        if (!finalStatePersisted) {
            operationRepository.casUpdateState(operationId,
                    OperationState.EXECUTING, OperationState.UNKNOWN,
                    record.version() + 1);
            auditPort.recordTerminal(operationId,
                    record.capabilityId(), record.capabilityVersion(),
                    ErrorCode.EXECUTION_UNKNOWN.name(), 0L,
                    "{\"operationId\":\"" + operationId + "\"}");
            return new ConfirmResult(false, OperationState.UNKNOWN.name(),
                    "Unable to persist final operation state");
        }

        // Record terminal audit event
        auditPort.recordTerminal(operationId,
                record.capabilityId(), record.capabilityVersion(),
                finalState.name(), 0,
                "{\"operationId\":\"" + operationId + "\"}");

        log.info("Confirm phase complete: operationId={}, finalState={}",
                operationId, finalState);

        return new ConfirmResult(true, finalState.name(),
                invocationResult.errorCode() != null
                        ? invocationResult.errorMessage()
                        : "Operation completed successfully");
    }

    private void recordRejected(OperationRecord record, String eventType,
                                ErrorCode errorCode, String reason) {
        auditPort.recordEvent(new com.ai.gateway.domain.model.AuditEvent(
                UUID.randomUUID().toString(), eventType, Instant.now(),
                record.principalDigest(), record.orgId(), record.operationId(),
                record.operationId(), record.capabilityId(), record.capabilityVersion(),
                record.manifestDigest(), record.snapshotVersion(), null, null,
                errorCode == null ? record.state().name() : errorCode.name(),
                0L, "{\"reason\":\"" + reason + "\"}"));
    }

    private void recordUnboundRejection(String operationId, Principal principal,
                                        ErrorCode errorCode, String reason) {
        auditPort.recordEvent(new com.ai.gateway.domain.model.AuditEvent(
                UUID.randomUUID().toString(), "CONFIRM_REJECTED", Instant.now(),
                computeDigest(principal.subject()), principal.orgId(), operationId,
                operationId, null, null, null, 0L, null, null,
                errorCode.name(), 0L, "{\"reason\":\"" + reason + "\"}"));
    }

    private OperationState persistExecutionFailure(OperationRecord record) {
        if (operationRepository.casUpdateState(record.operationId(),
                OperationState.EXECUTING, OperationState.FAILED,
                record.version() + 1)) {
            return OperationState.FAILED;
        }
        operationRepository.casUpdateState(record.operationId(),
                OperationState.EXECUTING, OperationState.UNKNOWN,
                record.version() + 1);
        return OperationState.UNKNOWN;
    }

    /**
     * Computes a SHA-256 digest of the given content.
     *
     * @param content the content to digest
     * @return the hex-encoded digest
     */
    private String computeDigest(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("SHA-256 algorithm not available", e);
        }
    }

    /**
     * The result of a Confirm operation.
     *
     * @param success whether the confirm and execution succeeded
     * @param finalState the final operation state name (e.g., "SUCCEEDED", "FAILED")
     * @param message a human-readable message or error description
     */
    public record ConfirmResult(
            boolean success,
            String finalState,
            String message
    ) {
    }
}
