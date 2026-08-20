package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.application.runtime.AgentToolCallUseCase;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.TelemetryPort;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Verifies a toolRef before entering the existing deterministic Agent dispatcher. */
public final class AgentHostToolCallUseCase {

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final InMemoryCatalogManager catalogManager;
    private final ToolReferenceService toolReferenceService;
    private final AgentToolCallUseCase delegate;
    private final TelemetryPort telemetry;

    public AgentHostToolCallUseCase(AuthenticationPort authenticationPort,
                                    AuthorizationPort authorizationPort,
                                    InMemoryCatalogManager catalogManager,
                                    ToolReferenceService toolReferenceService,
                                    AgentToolCallUseCase delegate,
                                    TelemetryPort telemetry) {
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
        this.catalogManager = Objects.requireNonNull(catalogManager);
        this.toolReferenceService = Objects.requireNonNull(toolReferenceService);
        this.delegate = Objects.requireNonNull(delegate);
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    public Result call(RequestContext requestContext,
                       String requestId,
                       String toolRef,
                       Map<String, Object> arguments,
                       String locale,
                       String idempotencyKey) {
        Objects.requireNonNull(requestContext, "requestContext must not be null");
        Principal principal;
        try {
            principal = authenticationPort.authenticate(requestContext);
        } catch (RuntimeException e) {
            return Result.error("AUTHENTICATION_FAILED", "Authentication failed", 0L, 0L);
        }
        return call(principal, requestId, toolRef, arguments, locale, idempotencyKey);
    }

    /** Internal Host entry point that reuses the Principal authenticated by the Connector. */
    public Result call(Principal principal,
                       String requestId,
                       String toolRef,
                       Map<String, Object> arguments,
                       String locale,
                       String idempotencyKey) {
        Objects.requireNonNull(principal, "principal must not be null");
        requireText(requestId, "requestId");
        requireText(toolRef, "toolRef");
        Objects.requireNonNull(arguments, "arguments must not be null");
        requireText(locale, "locale");
        requireText(idempotencyKey, "idempotencyKey");

        long started = System.nanoTime();
        String outcome = "error";
        try {
            ActiveCatalogView.ViewLease viewLease = catalogManager.acquireActiveView();
            if (viewLease == null) {
                return Result.error("CAPABILITY_UNAVAILABLE",
                        "Capability catalog unavailable", 0L, 0L);
            }
            try {
            ActiveCatalogView view = viewLease.view();
            PolicySnapshot policySnapshot = authorizationPort.resolvePolicySnapshot(principal);
            if (policySnapshot == null || !policySnapshot.healthy()
                    || policySnapshot.policyEpoch() <= 0) {
                return Result.error("POLICY_UNAVAILABLE", "Authorization policy unavailable",
                        view.catalogVersion(), 0L);
            }
            long policyEpoch = policySnapshot.policyEpoch();
            ToolReferenceService.Verification verification = toolReferenceService.verify(
                    toolRef, principal, view, policyEpoch);
            if (!verification.valid()) {
                return Result.error(errorCode(verification.failure()),
                        "Capability reference is not available",
                        view.catalogVersion(), policyEpoch);
            }
            boolean visible = view.visibleCapabilities(policySnapshot.visibility()).stream()
                    .anyMatch(verification.manifest()::equals);
            if (!visible) {
                return Result.error("CAPABILITY_UNAVAILABLE",
                        "Capability reference is not available",
                        view.catalogVersion(), policyEpoch);
            }

            AgentToolCallUseCase.Result delegated = delegate.callResolved(
                    requestId,
                    principal,
                    view.snapshot(),
                    verification.manifest(),
                    arguments,
                    locale,
                    idempotencyKey);
            outcome = delegated.status().name().toLowerCase(java.util.Locale.ROOT);
            return Result.from(delegated, verification.policyEpoch());
            } finally {
                viewLease.close();
            }
        } finally {
            telemetry.recordDuration("gateway.agent.call.duration", System.nanoTime() - started,
                    Map.of("resource", "call", "outcome", outcome));
        }
    }

    private static String errorCode(ToolReferenceService.Failure failure) {
        return switch (failure) {
            case EXPIRED -> "TOOL_REF_EXPIRED";
            case CATALOG_CHANGED -> "CATALOG_CHANGED";
            case POLICY_CHANGED -> "POLICY_CHANGED";
            case MALFORMED, SIGNATURE_INVALID, PRINCIPAL_MISMATCH, CAPABILITY_UNAVAILABLE ->
                    "CAPABILITY_UNAVAILABLE";
        };
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum Status { COMPLETED, CONFIRMATION_REQUIRED, ERROR }

    public record Result(
            Status status,
            Map<String, Object> data,
            String errorCode,
            String message,
            long catalogVersion,
            long policyEpoch,
            String operationId,
            String confirmationToken,
            boolean confirmationTokenHostOnly,
            Instant expiresAt) {

        private static Result from(AgentToolCallUseCase.Result result, long policyEpoch) {
            Status status = Status.valueOf(result.status().name());
            String token = result.token() == null ? null : result.token().token();
            return new Result(status, result.data(), result.errorCode(), result.message(),
                    result.snapshotVersion(), policyEpoch, result.operationId(), token,
                    token != null, result.expiresAt());
        }

        private static Result error(
                String errorCode, String message, long catalogVersion, long policyEpoch) {
            return new Result(Status.ERROR, null, errorCode, message,
                    catalogVersion, policyEpoch, null, null, false, null);
        }
    }
}
