package com.ai.gateway.application.agent;

import com.ai.gateway.application.operation.OperationCancelUseCase;
import com.ai.gateway.application.operation.OperationConfirmUseCase;
import com.ai.gateway.application.operation.OperationStatusUseCase;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.PrincipalFingerprint;
import com.ai.gateway.domain.service.Sha256Digest;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Protocol-neutral owner of Agent turn state and model-safe result mapping.
 * Transport adapters must not duplicate these checks.
 */
public final class AgentHostConnector {

    private final AuthenticationPort authenticationPort;
    private final AgentCapabilityResolver resolver;
    private final AgentHostToolCallUseCase callUseCase;
    private final AgentModelResultMapper modelResultMapper;
    private final AgentTurnStore turnStore;
    private final PendingConfirmationStore confirmationStore;
    private final OperationConfirmUseCase confirmUseCase;
    private final OperationCancelUseCase cancelUseCase;
    private final OperationStatusUseCase statusUseCase;
    private final TelemetryPort telemetry;
    private final AgentResolveAdmissionController resolveAdmission;

    public AgentHostConnector(
            AuthenticationPort authenticationPort,
            AgentCapabilityResolver resolver,
            AgentHostToolCallUseCase callUseCase,
            AgentModelResultMapper modelResultMapper,
            AgentTurnStore turnStore,
            PendingConfirmationStore confirmationStore,
            OperationConfirmUseCase confirmUseCase,
            OperationCancelUseCase cancelUseCase,
            TelemetryPort telemetry) {
        this(authenticationPort, resolver, callUseCase, modelResultMapper,
                turnStore, confirmationStore, confirmUseCase, cancelUseCase,
                null, telemetry,
                new AgentResolveAdmissionController(Integer.MAX_VALUE, telemetry));
    }

    public AgentHostConnector(
            AuthenticationPort authenticationPort,
            AgentCapabilityResolver resolver,
            AgentHostToolCallUseCase callUseCase,
            AgentModelResultMapper modelResultMapper,
            AgentTurnStore turnStore,
            PendingConfirmationStore confirmationStore,
            OperationConfirmUseCase confirmUseCase,
            OperationCancelUseCase cancelUseCase,
            TelemetryPort telemetry,
            AgentResolveAdmissionController resolveAdmission) {
        this(authenticationPort, resolver, callUseCase, modelResultMapper,
                turnStore, confirmationStore, confirmUseCase, cancelUseCase,
                null, telemetry, resolveAdmission);
    }

    public AgentHostConnector(
            AuthenticationPort authenticationPort,
            AgentCapabilityResolver resolver,
            AgentHostToolCallUseCase callUseCase,
            AgentModelResultMapper modelResultMapper,
            AgentTurnStore turnStore,
            PendingConfirmationStore confirmationStore,
            OperationConfirmUseCase confirmUseCase,
            OperationCancelUseCase cancelUseCase,
            OperationStatusUseCase statusUseCase,
            TelemetryPort telemetry,
            AgentResolveAdmissionController resolveAdmission) {
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.resolver = Objects.requireNonNull(resolver);
        this.callUseCase = Objects.requireNonNull(callUseCase);
        this.modelResultMapper = Objects.requireNonNull(modelResultMapper);
        this.turnStore = Objects.requireNonNull(turnStore);
        this.confirmationStore = Objects.requireNonNull(confirmationStore);
        this.confirmUseCase = Objects.requireNonNull(confirmUseCase);
        this.cancelUseCase = Objects.requireNonNull(cancelUseCase);
        this.statusUseCase = statusUseCase;
        this.telemetry = Objects.requireNonNull(telemetry);
        this.resolveAdmission = Objects.requireNonNull(resolveAdmission);
    }

    public ResolveResult resolve(
            RequestContext requestContext,
            String agentTurnId,
            String requestId,
            String query,
            int topK) {
        requireText(agentTurnId, "agentTurnId");
        requireText(requestId, "requestId");
        AgentResolveAdmissionController.Permit permit = resolveAdmission.tryAcquire();
        if (permit == null) {
            return ResolveResult.error("RESOLVE_CAPACITY_EXCEEDED");
        }
        try (permit) {
            long deadlineNanos = resolver.newResolveDeadlineNanos();
            AgentCapabilityResolver.AuthenticationResult authentication =
                    resolver.authenticate(requestContext, deadlineNanos);
            if (authentication.timedOut()) {
                return ResolveResult.error("RESOLVE_TIMEOUT");
            }
            if (authentication.capacityRejected()) {
                return ResolveResult.error("RESOLVE_CAPACITY_EXCEEDED");
            }
            if (authentication.principal() == null) {
                return ResolveResult.error("AUTHENTICATION_FAILED");
            }
            Principal principal = authentication.principal();
            AgentCapabilityResolver.Resolution resolution = resolver.resolve(
                    principal, query, topK, deadlineNanos);
            if (resolution.status() == AgentCapabilityResolver.Status.RESOLVED
                    && !resolution.candidates().isEmpty()) {
                AgentTurnState state = AgentTurnState.from(agentTurnId, requestId, resolution);
                try {
                    turnStore.put(principalDigest(principal), state);
                } catch (IllegalStateException e) {
                    telemetry.increment("gateway.agent.resolve.turn_store",
                            Map.of("outcome", "capacity_rejected"));
                    return ResolveResult.error("TURN_STATE_CAPACITY_EXCEEDED");
                }
                return new ResolveResult(resolution, state);
            }
            return new ResolveResult(resolution, null);
        }
    }

    public SchemaResult schema(
            RequestContext requestContext, String agentTurnId, String toolRef) {
        StoredTurn turn = findTurn(requestContext, agentTurnId, toolRef);
        if (turn == null) {
            return SchemaResult.error("TOOL_REF_NOT_IN_TURN");
        }
        AgentTurnStore.StoredTurn claimed = turnStore.claimTool(
                turn.principalDigest(), agentTurnId, toolRef).orElse(null);
        if (claimed == null) {
            return SchemaResult.error("TOOL_REF_NOT_SELECTED");
        }
        AgentCapabilityResolver.SchemaResult result = resolver.loadSchema(
                turn.principal(), toolRef);
        AgentTurnState state = claimed.state();
        if (result.status() == AgentCapabilityResolver.Status.RESOLVED) {
            state = claimed.state().select(toolRef, result.schemaClass());
            turnStore.replace(turn.principalDigest(), state);
        }
        return new SchemaResult(result, state);
    }

    public CallResult call(
            RequestContext requestContext,
            String agentTurnId,
            String requestId,
            String toolRef,
            Map<String, Object> arguments,
            String locale,
            String idempotencyKey) {
        StoredTurn turn = findTurn(requestContext, agentTurnId, toolRef);
        if (turn == null) {
            return CallResult.error("TOOL_REF_NOT_IN_TURN");
        }
        AgentTurnState state = turnStore.claimTool(
                turn.principalDigest(), agentTurnId, toolRef)
                .map(AgentTurnStore.StoredTurn::state).orElse(null);
        if (state == null) {
            return CallResult.error("TOOL_REF_NOT_SELECTED");
        }

        Principal principal = turn.principal();
        AgentHostToolCallUseCase.Result gatewayResult = callUseCase.call(
                principal, requestId, toolRef, arguments, locale, idempotencyKey);
        AgentModelResultMapper.ModelResult safeResult;
        try {
            safeResult = modelResultMapper.map(
                    gatewayResult, turn.principalDigest(), argumentsDigest(arguments));
        } catch (IllegalStateException e) {
            if (gatewayResult.operationId() != null) {
                cancelUseCase.cancel(gatewayResult.operationId(), principal);
            }
            telemetry.increment("gateway.agent.confirmation_store",
                    Map.of("outcome", "capacity_rejected"));
            return CallResult.error("CONFIRMATION_STATE_CAPACITY_EXCEEDED");
        }
        AgentTurnState updated = state;
        if (safeResult.operationId() != null) {
            updated = state.withPendingConfirmation(safeResult.operationId());
            turnStore.replace(turn.principalDigest(), updated);
        }
        String confirmationToken = gatewayResult.status()
                == AgentHostToolCallUseCase.Status.CONFIRMATION_REQUIRED
                ? gatewayResult.confirmationToken() : null;
        return new CallResult(safeResult, updated, confirmationToken);
    }

    public AgentTurnState recordArgumentRepair(
            RequestContext requestContext, String agentTurnId) {
        StoredTurn turn = findTurn(requestContext, agentTurnId, null);
        if (turn == null) {
            throw new IllegalArgumentException("agent turn is unavailable");
        }
        AgentTurnState repaired = turn.state().recordArgumentRepair();
        turnStore.replace(turn.principalDigest(), repaired);
        return repaired;
    }

    /** UI-only event. Model messages cannot construct this protocol type. */
    public ConfirmationResult confirm(UserConfirmationEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Principal principal = authenticate(event.requestContext());
        if (principal == null) {
            return new ConfirmationResult(false, "AUTHENTICATION_FAILED", null);
        }
        String digest = principalDigest(principal);
        PendingConfirmationState pending = confirmationStore
                .beginConfirm(event.operationId(), digest).orElse(null);
        if (pending == null) {
            return new ConfirmationResult(false, "CONFIRMATION_NOT_AVAILABLE", null);
        }
        OperationConfirmUseCase.ConfirmResult result;
        try {
            result = confirmUseCase.confirm(
                    event.operationId(), pending.confirmationToken(), principal);
        } finally {
            confirmationStore.remove(event.operationId());
        }
        PendingConfirmationState.Status status = result.success()
                ? PendingConfirmationState.Status.CONFIRMED
                : "EXPIRED".equals(result.finalState())
                        ? PendingConfirmationState.Status.EXPIRED
                        : "UNKNOWN".equals(result.finalState())
                                ? PendingConfirmationState.Status.UNKNOWN
                                : PendingConfirmationState.Status.REJECTED;
        pending.transition(status);
        telemetry.increment("gateway.agent.confirm", Map.of(
                "outcome", result.success() ? "confirmed" : "rejected"));
        return new ConfirmationResult(result.success(), result.finalState(), result.message());
    }

    /** UI-only event. Model messages cannot construct this protocol type. */
    public CancellationResult cancel(UserCancellationEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Principal principal = authenticate(event.requestContext());
        if (principal == null) {
            return new CancellationResult(false, "AUTHENTICATION_FAILED", null);
        }
        String digest = principalDigest(principal);
        PendingConfirmationState pending = confirmationStore
                .beginConfirm(event.operationId(), digest).orElse(null);
        if (pending == null) {
            return new CancellationResult(false, "CONFIRMATION_NOT_AVAILABLE", null);
        }
        OperationCancelUseCase.CancelResult result;
        try {
            result = cancelUseCase.cancel(event.operationId(), principal);
        } finally {
            confirmationStore.remove(event.operationId());
        }
        pending.transition(result.success() ? PendingConfirmationState.Status.REJECTED
                : PendingConfirmationState.Status.UNKNOWN);
        telemetry.increment("gateway.agent.cancel", Map.of(
                "outcome", result.success() ? "cancelled" : "rejected"));
        return new CancellationResult(result.success(), result.state(), result.message());
    }

    /** Trusted Host query against the canonical Operation state. */
    public OperationStatusResult status(RequestContext requestContext, String operationId) {
        requireText(operationId, "operationId");
        Principal principal = authenticate(requestContext);
        if (principal == null) {
            return OperationStatusResult.error("AUTHENTICATION_FAILED");
        }
        if (statusUseCase == null) {
            return OperationStatusResult.error("STATUS_UNAVAILABLE");
        }
        OperationRecord record = statusUseCase.query(operationId);
        if (record == null || record.orgId() != principal.orgId()
                || !record.principalDigest().equals(
                        Sha256Digest.sha256Hex(principal.subject()))) {
            return OperationStatusResult.error("OPERATION_NOT_FOUND");
        }
        return new OperationStatusResult(true, record.state().name(),
                record.expiresAt(), null);
    }

    private StoredTurn findTurn(RequestContext context, String turnId, String toolRef) {
        requireText(turnId, "agentTurnId");
        Principal principal = authenticate(context);
        if (principal == null) {
            return null;
        }
        String digest = principalDigest(principal);
        AgentTurnState state = turnStore.find(digest, turnId)
                .map(AgentTurnStore.StoredTurn::state).orElse(null);
        if (state == null || state.expiresAt().isBefore(Instant.now())
                || (toolRef != null && !state.allows(toolRef))) {
            return null;
        }
        return new StoredTurn(digest, state, principal);
    }

    private Principal authenticate(RequestContext context) {
        try {
            return authenticationPort.authenticate(Objects.requireNonNull(
                    context, "requestContext must not be null"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String principalDigest(Principal principal) {
        return PrincipalFingerprint.digest(principal);
    }

    private static String argumentsDigest(Map<String, Object> arguments) {
        return Sha256Digest.sha256Hex(String.valueOf(arguments));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record StoredTurn(String principalDigest, AgentTurnState state, Principal principal) {
    }

    public record ResolveResult(
            AgentCapabilityResolver.Resolution resolution,
            AgentTurnState state) {
        private static ResolveResult error(String errorCode) {
            return new ResolveResult(new AgentCapabilityResolver.Resolution(
                    AgentCapabilityResolver.Status.ERROR, errorCode, 0L, 0L,
                    java.util.List.of(), null, null), null);
        }
    }

    public record SchemaResult(
            AgentCapabilityResolver.SchemaResult result,
            AgentTurnState state) {
        private static SchemaResult error(String errorCode) {
            return new SchemaResult(new AgentCapabilityResolver.SchemaResult(
                    AgentCapabilityResolver.Status.ERROR, errorCode, null, null,
                    Map.of(), null), null);
        }
    }

    public record CallResult(AgentModelResultMapper.ModelResult result,
                             AgentTurnState state,
                             String confirmationTokenHostOnly) {
        private static CallResult error(String errorCode) {
            return new CallResult(new AgentModelResultMapper.ModelResult(
                    AgentModelResultMapper.ModelResult.Status.ERROR, null,
                    errorCode, "Agent turn rejected", null, null), null, null);
        }
    }

    public record UserConfirmationEvent(RequestContext requestContext, String operationId) {
        public UserConfirmationEvent {
            Objects.requireNonNull(requestContext);
            requireText(operationId, "operationId");
        }
    }

    public record UserCancellationEvent(RequestContext requestContext, String operationId) {
        public UserCancellationEvent {
            Objects.requireNonNull(requestContext);
            requireText(operationId, "operationId");
        }
    }

    public record ConfirmationResult(boolean success, String state, String message) {
    }

    public record CancellationResult(boolean success, String state, String message) {
    }

    public record OperationStatusResult(
            boolean found, String state, Instant expiresAt, String errorCode) {
        private static OperationStatusResult error(String errorCode) {
            return new OperationStatusResult(false, null, null, errorCode);
        }
    }
}
