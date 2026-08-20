package com.ai.gateway.application.agent;

import java.util.Optional;

/** Private Host-side store for confirmation tokens and their state. */
public interface PendingConfirmationStore {

    void put(PendingConfirmationState state);

    Optional<PendingConfirmationState> find(String operationId, String principalDigest);

    Optional<PendingConfirmationState> beginConfirm(String operationId, String principalDigest);

    void replace(PendingConfirmationState state);

    void remove(String operationId);
}
