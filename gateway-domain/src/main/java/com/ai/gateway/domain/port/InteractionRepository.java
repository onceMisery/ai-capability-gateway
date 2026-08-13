package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.NlInteraction;

import java.util.Optional;

/**
 * Port for storing and retrieving clarification interaction sessions.
 *
 * <p>(Clarification Session) specifies that when the model
 * returns a clarification decision, the gateway stores a short-lived
 * interaction record containing:</p>
 * <ul>
 * <li>{@code interactionId} — the unique session identifier.</li>
 * <li>{@code principalDigest} — the digest of the requesting Principal.</li>
 * <li>{@code snapshotVersion} — the fixed catalog snapshot version.</li>
 * <li>{@code candidateCapabilityIds} — the candidate capability
 * ID/version set.</li>
 * <li>{@code confirmedParams} — non-sensitive parameters already
 * confirmed.</li>
 * <li>{@code pendingFields} — the fields still needing user input.</li>
 * <li>{@code expiresAt} — the short expiration time.</li>
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
 * change also forces a fresh start. The interaction must not inherit old
 * authorization decisions.</p>
 *
 * <p>Adapters implementing this port persist interaction records with
 * short TTLs (typically in PostgreSQL or an in-memory store). The port
 * is a pure abstraction with no framework dependencies.</p>
 *
 * @see NlInteraction
 * @since 0.1.0
 */
public interface InteractionRepository {

    /**
     * Persists a clarification interaction session.
     *
     * <p>: stored when the model returns a CLARIFY decision.
     * The interaction has a short expiration time. Subsequent answers
     * may only supplement missing information or disambiguate within
     * the original candidate set.</p>
     *
     * @param interaction the interaction to persist; never {@code null}
     */
    void save(NlInteraction interaction);

    /**
     * Finds an interaction session by its ID.
     *
     * <p>Used to resume a clarification session when the user provides
     * additional input. The caller must verify the interaction has not
     * expired and the Principal has not changed.</p>
     *
     * @param interactionId the unique interaction identifier
     * @return the interaction, or empty if not found or expired
     */
    Optional<NlInteraction> findById(String interactionId);

    /**
     * Deletes an interaction session by its ID.
     *
     * <p>: the interaction must be immediately invalidated when
     * an intent jump is detected, the Principal changes, the session
     * expires, the capability is suspended, or the policy changes. No old
     * authorization, candidate set, or snapshot may be inherited.</p>
     *
     * @param interactionId the unique interaction identifier
     */
    void deleteById(String interactionId);
}
