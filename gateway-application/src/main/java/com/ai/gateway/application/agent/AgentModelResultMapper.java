package com.ai.gateway.application.agent;

import com.ai.gateway.application.agent.AgentHostToolCallUseCase.Result;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Converts gateway responses into results safe to send back to the model. */
public final class AgentModelResultMapper {

    private final PendingConfirmationStore confirmationStore;

    public AgentModelResultMapper(PendingConfirmationStore confirmationStore) {
        this.confirmationStore = Objects.requireNonNull(confirmationStore);
    }

    public ModelResult map(Result result, String principalDigest, String argumentsDigest) {
        Objects.requireNonNull(result, "result must not be null");
        if (result.status() == AgentHostToolCallUseCase.Status.CONFIRMATION_REQUIRED) {
            if (result.operationId() == null || result.confirmationToken() == null
                    || result.expiresAt() == null) {
                throw new IllegalArgumentException("confirmation response is incomplete");
            }
            confirmationStore.put(new PendingConfirmationState(
                    result.operationId(), result.confirmationToken(), principalDigest,
                    argumentsDigest, result.message(), result.expiresAt(),
                    PendingConfirmationState.Status.PENDING));
            return new ModelResult(
                    ModelResult.Status.CONFIRMATION_REQUIRED, null, null,
                    result.message(), result.operationId(), result.expiresAt());
        }
        return new ModelResult(
                result.status() == AgentHostToolCallUseCase.Status.COMPLETED
                        ? ModelResult.Status.COMPLETED : ModelResult.Status.ERROR,
                result.data(), result.errorCode(), result.message(), null, null);
    }

    public record ModelResult(
            Status status,
            Map<String, Object> data,
            String errorCode,
            String message,
            String operationId,
            Instant expiresAt
    ) {
        public enum Status { COMPLETED, CONFIRMATION_REQUIRED, ERROR }
    }
}
