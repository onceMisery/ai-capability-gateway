package com.ai.gateway.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A clarification interaction session for multi-turn natural-language routing.
 *
 * <p>Specifies that when the model returns a clarification
 * decision, the gateway stores a short-lived interaction record containing:</p>
 *
 * <ul>
 * <li>{@code interactionId} - the unique session identifier.</li>
 * <li>{@code principalDigest} - the digest of the requesting Principal.</li>
 * <li>{@code snapshotVersion} - the fixed catalog snapshot version.</li>
 * <li>{@code candidateCapabilityIds} - the candidate capability ID/version set.</li>
 * <li>{@code confirmedParams} - non-sensitive parameters already confirmed.</li>
 * <li>{@code pendingFields} - the fields still needing user input.</li>
 * <li>{@code expiresAt} - the short expiration time.</li>
 * </ul>
 *
 * <p>Subsequent answers may only supplement missing information or
 * disambiguate within the original candidate set. The gateway must detect
 * intent jumps: if the user's reply triggers a NO_MATCH or
 * selects an alias outside the original candidate set, the current
 * interactionId is immediately invalidated, and a full routing pipeline
 * restart is required — no old authorization, candidate set, or snapshot
 * may be inherited.</p>
 *
 * <p>Principal change, session expiry, capability suspension, or policy
 * change also forces a fresh start.</p>
 *
 * @param interactionId the unique interaction identifier
 * @param principalDigest the digest of the requesting Principal
 * @param snapshotVersion the fixed catalog snapshot version
 * @param candidateCapabilityIds the candidate capability identifiers
 * @param confirmedParams the non-sensitive confirmed parameters
 * @param pendingFields the fields awaiting user input
 * @param expiresAt the interaction expiration time
 * @since 0.1.0
 */
public record NlInteraction(
        String interactionId,
        String principalDigest,
        long snapshotVersion,
        List<String> candidateCapabilityIds,
        Map<String, Object> confirmedParams,
        List<String> pendingFields,
        Instant expiresAt
) {

    /**
     * Compact constructor performing defensive copying and null checks.
     *
     * @param interactionId the interaction ID
     * @param principalDigest the principal digest
     * @param snapshotVersion the snapshot version
     * @param candidateCapabilityIds the candidate IDs
     * @param confirmedParams the confirmed params
     * @param pendingFields the pending fields
     * @param expiresAt the expiration time
     */
    public NlInteraction {
        java.util.Objects.requireNonNull(interactionId, "interactionId must not be null");
        java.util.Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        java.util.Objects.requireNonNull(candidateCapabilityIds, "candidateCapabilityIds must not be null");
        java.util.Objects.requireNonNull(confirmedParams, "confirmedParams must not be null");
        java.util.Objects.requireNonNull(pendingFields, "pendingFields must not be null");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        candidateCapabilityIds = List.copyOf(candidateCapabilityIds);
        confirmedParams = Map.copyOf(confirmedParams);
        pendingFields = List.copyOf(pendingFields);
    }

    /**
     * Returns whether this interaction has expired.
     *
     * @param now the current time
     * @return {@code true} if the current time is after {@code expiresAt}
     */
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
