package com.ai.gateway.application.runtime;

import com.ai.gateway.application.operation.OperationPrepareUseCase;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ConfirmationToken;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Protocol-neutral Agent tool dispatcher.
 *
 * <p>The dispatcher repeats the manifest and visibility checks at execution
 * time. A resolve response is therefore an optimization for context size, not
 * an authorization grant.</p>
 */
public final class AgentToolCallUseCase {

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final CatalogPort catalogPort;
    private final StructuredInvocationUseCase structuredInvocationUseCase;
    private final OperationPrepareUseCase operationPrepareUseCase;
    private final String environment;

    public AgentToolCallUseCase(AuthenticationPort authenticationPort,
                                AuthorizationPort authorizationPort,
                                CatalogPort catalogPort,
                                StructuredInvocationUseCase structuredInvocationUseCase,
                                OperationPrepareUseCase operationPrepareUseCase,
                                String environment) {
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
        this.catalogPort = Objects.requireNonNull(catalogPort);
        this.structuredInvocationUseCase = Objects.requireNonNull(structuredInvocationUseCase);
        this.operationPrepareUseCase = Objects.requireNonNull(operationPrepareUseCase);
        this.environment = requireText(environment, "environment");
    }

    /** Dispatches a host-selected capability after rechecking its binding. */
    public Result call(RequestContext requestContext,
                       String requestId,
                       String capabilityId,
                       String capabilityVersion,
                       Map<String, Object> modelArguments,
                       String locale,
                       long expectedSnapshotVersion,
                       String clientIdempotencyKey) {
        Objects.requireNonNull(requestContext, "requestContext must not be null");
        requireText(requestId, "requestId");
        requireText(capabilityId, "capabilityId");
        requireText(capabilityVersion, "capabilityVersion");
        Objects.requireNonNull(modelArguments, "modelArguments must not be null");
        requireText(locale, "locale");

        var principal = authenticationPort.authenticate(requestContext);
        CatalogSnapshot snapshot = catalogPort.loadCurrentSnapshot(environment);
        if (snapshot == null || snapshot.snapshotVersion() <= 0) {
            return error("CAPABILITY_UNAVAILABLE", "Capability catalog unavailable", 0L,
                    capabilityId, capabilityVersion);
        }
        if (snapshot.snapshotVersion() != expectedSnapshotVersion) {
            return error("STALE_SNAPSHOT", "Agent tool snapshot is stale",
                    snapshot.snapshotVersion(), capabilityId, capabilityVersion);
        }

        CapabilityManifest manifest = snapshot.capabilities().stream()
                .filter(candidate -> capabilityId.equals(candidate.metadata().id())
                        && capabilityVersion.equals(candidate.metadata().version()))
                .findFirst()
                .orElse(null);
        if (manifest == null) {
            return error("NO_CAPABILITY_MATCH", "Capability is not available",
                    snapshot.snapshotVersion(), capabilityId, capabilityVersion);
        }
        List<CapabilityManifest> visible = authorizationPort.filterVisibleCapabilities(
                principal, List.of(manifest));
        if (visible == null || visible.isEmpty()) {
            return error("PERMISSION_DENIED", "Capability is not available",
                    snapshot.snapshotVersion(), capabilityId, capabilityVersion);
        }

        if (manifest.spec().risk() == RiskLevel.WRITE_HIGH) {
            return error("HIGH_RISK_WRITE_BLOCKED",
                    "High-risk writes are unavailable to Agent tools",
                    snapshot.snapshotVersion(), capabilityId, capabilityVersion);
        }
        return callResolved(requestId, principal, snapshot, manifest,
                modelArguments, locale, clientIdempotencyKey);
    }

    /** Dispatches a capability already pinned by a trusted active catalog view. */
    public Result callResolved(String requestId,
                               com.ai.gateway.domain.model.Principal principal,
                               CatalogSnapshot snapshot,
                               CapabilityManifest manifest,
                               Map<String, Object> modelArguments,
                               String locale,
                               String clientIdempotencyKey) {
        return callResolved(requestId, principal, snapshot, manifest, modelArguments,
                locale, clientIdempotencyKey, true);
    }

    public Result callResolved(String requestId,
                               com.ai.gateway.domain.model.Principal principal,
                               CatalogSnapshot snapshot,
                               CapabilityManifest manifest,
                               Map<String, Object> modelArguments,
                               String locale,
                               String clientIdempotencyKey,
                               boolean allowWritePrepare) {
        requireText(requestId, "requestId");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(modelArguments, "modelArguments must not be null");
        requireText(locale, "locale");
        String capabilityId = manifest.metadata().id();
        String capabilityVersion = manifest.metadata().version();
        if (manifest.spec().risk() == RiskLevel.WRITE_HIGH) {
            return error("HIGH_RISK_WRITE_BLOCKED",
                    "High-risk writes are unavailable to Agent tools",
                    snapshot.snapshotVersion(), capabilityId, capabilityVersion);
        }
        if (manifest.spec().risk() == RiskLevel.READ_ONLY) {
            return fromStructured(structuredInvocationUseCase.invokeResolved(
                    requestId, principal, snapshot, manifest,
                    modelArguments, locale));
        }
        if (!allowWritePrepare) {
            return error("MCP_WRITE_DISABLED",
                    "Write capability is disabled for this MCP client",
                    snapshot.snapshotVersion(), capabilityId, capabilityVersion);
        }

        OperationPrepareUseCase.PrepareResult prepared = operationPrepareUseCase.prepareResolved(
                requestId, principal, snapshot, manifest, modelArguments,
                locale, clientIdempotencyKey);
        if (!prepared.success()) {
            return error("PREPARE_FAILED", prepared.error(), snapshot.snapshotVersion(),
                    capabilityId, capabilityVersion);
        }
        return new Result(Status.CONFIRMATION_REQUIRED, null, null, prepared.summary(),
                snapshot.snapshotVersion(), capabilityId, capabilityVersion,
                prepared.operationId(), prepared.token(), prepared.expiresAt());
    }

    private Result fromStructured(StructuredInvocationUseCase.Result result) {
        if (result.status() == StructuredInvocationUseCase.Status.COMPLETED) {
            return new Result(Status.COMPLETED, result.data(), null, result.message(),
                    result.snapshotVersion(), result.capabilityId(), result.capabilityVersion(),
                    null, null, null);
        }
        return error(result.errorCode(), result.message(), result.snapshotVersion(),
                result.capabilityId(), result.capabilityVersion());
    }

    private static Result error(String errorCode, String message, long snapshotVersion,
                                String capabilityId, String capabilityVersion) {
        return new Result(Status.ERROR, null, errorCode, message, snapshotVersion,
                capabilityId, capabilityVersion, null, null, null);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum Status { COMPLETED, CONFIRMATION_REQUIRED, ERROR }

    public record Result(Status status,
                         Map<String, Object> data,
                         String errorCode,
                         String message,
                         long snapshotVersion,
                         String capabilityId,
                         String capabilityVersion,
                         String operationId,
                         ConfirmationToken token,
                         Instant expiresAt) {
    }
}
