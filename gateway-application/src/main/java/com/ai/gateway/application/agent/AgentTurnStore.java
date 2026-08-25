package com.ai.gateway.application.agent;

import java.util.Optional;

/** Host-private storage for one authenticated Agent turn. */
public interface AgentTurnStore {

    void put(String principalDigest, AgentTurnState state);

    default void putResolved(String principalDigest, AgentTurnState state,
                             String resolveFingerprint,
                             AgentCapabilityResolver.Resolution resolution) {
        put(principalDigest, state);
    }

    default Optional<ResolvedTurn> findResolved(
            String principalDigest, String agentTurnId, String resolveFingerprint) {
        return Optional.empty();
    }

    Optional<StoredTurn> find(String principalDigest, String agentTurnId);

    Optional<StoredTurn> claimTool(
            String principalDigest, String agentTurnId, String toolRef);

    void replace(String principalDigest, AgentTurnState state);

    record StoredTurn(String principalDigest, AgentTurnState state) {
    }

    record ResolvedTurn(String principalDigest, AgentTurnState state,
                        String resolveFingerprint,
                        AgentCapabilityResolver.Resolution resolution) {
    }
}
