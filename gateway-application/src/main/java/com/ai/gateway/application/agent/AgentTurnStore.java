package com.ai.gateway.application.agent;

import java.util.Optional;

/** Host-private storage for one authenticated Agent turn. */
public interface AgentTurnStore {

    void put(String principalDigest, AgentTurnState state);

    Optional<StoredTurn> find(String principalDigest, String agentTurnId);

    Optional<StoredTurn> claimTool(
            String principalDigest, String agentTurnId, String toolRef);

    void replace(String principalDigest, AgentTurnState state);

    record StoredTurn(String principalDigest, AgentTurnState state) {
    }
}
