package com.ai.gateway.application.agent;

import java.time.Instant;
import java.util.Objects;

/** Host-private confirmation state. Never serialize this record into model messages. */
public record PendingConfirmationState(
        String operationId,
        String confirmationToken,
        String principalDigest,
        String argumentsDigest,
        String summary,
        Instant expiresAt,
        Status status
) {

    public PendingConfirmationState {
        requireText(operationId, "operationId");
        status = Objects.requireNonNull(status, "status must not be null");
        if (status == Status.PENDING || status == Status.CONFIRMING) {
            requireText(confirmationToken, "confirmationToken");
        } else if (confirmationToken != null) {
            throw new IllegalArgumentException(
                    "terminal confirmation state must not retain a token");
        }
        requireText(principalDigest, "principalDigest");
        requireText(argumentsDigest, "argumentsDigest");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public enum Status { PENDING, CONFIRMING, CONFIRMED, REJECTED, EXPIRED, UNKNOWN }

    public PendingConfirmationState transition(Status next) {
        Objects.requireNonNull(next, "next must not be null");
        boolean valid = switch (status) {
            case PENDING -> next == Status.CONFIRMING || next == Status.REJECTED
                    || next == Status.EXPIRED;
            case CONFIRMING -> next == Status.CONFIRMED || next == Status.REJECTED
                    || next == Status.UNKNOWN;
            case CONFIRMED, REJECTED, EXPIRED, UNKNOWN -> false;
        };
        if (!valid) {
            throw new IllegalStateException("invalid confirmation state transition: "
                    + status + " -> " + next);
        }
        String nextToken = next == Status.CONFIRMING ? confirmationToken : null;
        return new PendingConfirmationState(operationId, nextToken, principalDigest,
                argumentsDigest, summary, expiresAt, next);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
