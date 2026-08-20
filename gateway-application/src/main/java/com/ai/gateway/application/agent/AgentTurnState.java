package com.ai.gateway.application.agent;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Immutable Host-owned state for one model turn. */
public record AgentTurnState(
        String agentTurnId,
        String resolveRequestId,
        long catalogVersion,
        long policyEpoch,
        Instant expiresAt,
        Set<String> allowedToolRefs,
        String selectedToolRef,
        CapabilityPublicProjectionService.SchemaClass schemaClass,
        int argumentRepairCount,
        String pendingConfirmationOperationId
) {

    public AgentTurnState {
        requireText(agentTurnId, "agentTurnId");
        requireText(resolveRequestId, "resolveRequestId");
        if (catalogVersion <= 0 || policyEpoch <= 0) {
            throw new IllegalArgumentException("catalogVersion and policyEpoch must be positive");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        allowedToolRefs = Set.copyOf(allowedToolRefs == null ? Set.of() : allowedToolRefs);
        if (argumentRepairCount < 0 || argumentRepairCount > 1) {
            throw new IllegalArgumentException("argumentRepairCount must be between 0 and 1");
        }
        if (selectedToolRef != null && !allowedToolRefs.contains(selectedToolRef)) {
            throw new IllegalArgumentException("selectedToolRef must be in allowedToolRefs");
        }
    }

    public static AgentTurnState from(
            String agentTurnId, String resolveRequestId,
            AgentCapabilityResolver.Resolution resolution) {
        Objects.requireNonNull(resolution, "resolution must not be null");
        if (resolution.status() != AgentCapabilityResolver.Status.RESOLVED
                || resolution.candidates().isEmpty()) {
            throw new IllegalArgumentException("a turn requires a resolved candidate set");
        }
        Set<String> refs = new LinkedHashSet<>();
        resolution.candidates().forEach(candidate -> refs.add(candidate.toolRef()));
        return new AgentTurnState(agentTurnId, resolveRequestId,
                resolution.catalogVersion(), resolution.policyEpoch(), resolution.expiresAt(),
                refs, null, null, 0, null);
    }

    public AgentTurnState select(String toolRef,
                                 CapabilityPublicProjectionService.SchemaClass selectedSchemaClass) {
        requireText(toolRef, "toolRef");
        if (!allowedToolRefs.contains(toolRef)) {
            throw new IllegalArgumentException("toolRef is not allowed in this turn");
        }
        return new AgentTurnState(agentTurnId, resolveRequestId, catalogVersion, policyEpoch,
                expiresAt, allowedToolRefs, toolRef, selectedSchemaClass,
                argumentRepairCount, pendingConfirmationOperationId);
    }

    public AgentTurnState recordArgumentRepair() {
        if (argumentRepairCount >= 1) {
            throw new IllegalStateException("argument repair budget exhausted");
        }
        return new AgentTurnState(agentTurnId, resolveRequestId, catalogVersion, policyEpoch,
                expiresAt, allowedToolRefs, selectedToolRef, schemaClass,
                argumentRepairCount + 1, pendingConfirmationOperationId);
    }

    public AgentTurnState withPendingConfirmation(String operationId) {
        requireText(operationId, "operationId");
        return new AgentTurnState(agentTurnId, resolveRequestId, catalogVersion, policyEpoch,
                expiresAt, allowedToolRefs, selectedToolRef, schemaClass,
                argumentRepairCount, operationId);
    }

    public boolean allows(String toolRef) {
        return toolRef != null && allowedToolRefs.contains(toolRef)
                && !expiresAt.isBefore(Instant.now());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
