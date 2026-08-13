package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ModelDecision;
import com.ai.gateway.domain.model.NlInteraction;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.InteractionRepository;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.ai.gateway.domain.service.AliasGenerator;
import com.ai.gateway.domain.service.ThresholdEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Use case for continuing a clarification session in multi-turn natural-language
 * routing.
 *
 * <p>When the model returns a clarification decision, the gateway stores a
 * short-lived interaction record. Subsequent answers may only supplement
 * missing information or disambiguate within the original candidate set.</p>
 *
 * <p>The clarification continuation performs the following checks:</p>
 * <ol>
 * <li>Check that the interaction has not expired.</li>
 * <li>Check that the Principal has not changed.</li>
 * <li>Check that the capability has not been suspended.</li>
 * <li>Run SELECT/CLARIFY/NO_MATCH on the current fixed candidate set.</li>
 * <li>Intent breakout detection: if NO_MATCH or the selected alias is not
 * in the original candidate set, invalidate the interactionId and
 * signal that a full pipeline restart is required.</li>
 * </ol>
 *
 * <p>Principal change, session expiry, capability suspension, or policy
 * change also forces a fresh start. The interaction must not inherit old
 * authorization decisions.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable per-request state.</p>
 *
 * @see InteractionRepository
 * @see CandidateRetriever
 * @see LlmRouterPort
 * @since 0.1.0
 */
public final class ClarificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ClarificationUseCase.class);

    private final InteractionRepository interactionRepository;
    private final CandidateRetriever candidateRetriever;
    private final LlmRouterPort llmRouterPort;
    private final AliasGenerator aliasGenerator;
    private final ThresholdEvaluator thresholdEvaluator;
    private final CatalogPort catalogPort;
    private final String environment;

    /**
     * Constructs a new ClarificationUseCase with the required dependencies.
     *
     * @param interactionRepository the repository for storing and retrieving interactions
     * @param candidateRetriever the BM25 candidate retriever
     * @param llmRouterPort the LLM routing port
     * @param aliasGenerator the short alias generator
     * @param thresholdEvaluator the threshold evaluator
     * @param catalogPort the catalog port for checking capability suspension
     * @throws NullPointerException if any argument is null
     */
    public ClarificationUseCase(InteractionRepository interactionRepository,
                                 CandidateRetriever candidateRetriever,
                                 LlmRouterPort llmRouterPort,
                                 AliasGenerator aliasGenerator,
                                 ThresholdEvaluator thresholdEvaluator,
                                 CatalogPort catalogPort,
                                 String environment) {
        this.interactionRepository = java.util.Objects.requireNonNull(interactionRepository,
                "interactionRepository must not be null");
        this.candidateRetriever = java.util.Objects.requireNonNull(candidateRetriever,
                "candidateRetriever must not be null");
        this.llmRouterPort = java.util.Objects.requireNonNull(llmRouterPort,
                "llmRouterPort must not be null");
        this.aliasGenerator = java.util.Objects.requireNonNull(aliasGenerator,
                "aliasGenerator must not be null");
        this.thresholdEvaluator = java.util.Objects.requireNonNull(thresholdEvaluator,
                "thresholdEvaluator must not be null");
        this.catalogPort = java.util.Objects.requireNonNull(catalogPort,
                "catalogPort must not be null");
        this.environment = java.util.Objects.requireNonNull(environment,
                "environment must not be null");
    }

    /**
     * Continues a clarification session with the user's additional input
     *
     * @param interactionId the clarification interaction ID
     * @param text the user's additional input text
     * @param principalDigest the current principal's digest for identity verification
     * @return the clarification result
     * @throws NullPointerException if any argument is null
     */
    public ClarificationResult continueClarification(String interactionId,
                                                      String text,
                                                      String principalDigest) {
        java.util.Objects.requireNonNull(interactionId, "interactionId must not be null");
        java.util.Objects.requireNonNull(text, "text must not be null");
        java.util.Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        log.info("Continuing clarification: interactionId={}", interactionId);

        // Load the interaction
        Optional<NlInteraction> interactionOpt = interactionRepository.findById(interactionId);
        if (interactionOpt.isEmpty()) {
            log.warn("Interaction not found: {}", interactionId);
            return new ClarificationResult(ClarificationStatus.INVALID,
                    null, null, null, "Interaction not found or already expired");
        }

        NlInteraction interaction = interactionOpt.get();

        // Check 1: Interaction not expired
        if (interaction.isExpired(Instant.now())) {
            log.warn("Interaction expired: {}", interactionId);
            interactionRepository.deleteById(interactionId);
            return new ClarificationResult(ClarificationStatus.INVALID,
                    null, null, null, "Interaction has expired");
        }

        // Check 2: Principal not changed
        if (!interaction.principalDigest().equals(principalDigest)) {
            log.warn("Principal changed for interaction: {}", interactionId);
            interactionRepository.deleteById(interactionId);
            return new ClarificationResult(ClarificationStatus.INVALID,
                    null, null, null,
                    "Principal has changed — fresh start required");
        }

        // Check 3: Capability not suspended
        // Verify that the candidate capabilities are still available
        for (String capabilityId : interaction.candidateCapabilityIds()) {
            try {
                // Check if the capability is still in the current snapshot
                var currentSnapshot = catalogPort.loadCurrentSnapshot(environment);
                if (currentSnapshot != null) {
                    boolean stillExists = currentSnapshot.capabilities().stream()
                            .anyMatch(c -> c.metadata().id().equals(capabilityId));
                    if (!stillExists) {
                        log.warn("Capability {} no longer available (suspended/retired)",
                                capabilityId);
                        interactionRepository.deleteById(interactionId);
                        return new ClarificationResult(ClarificationStatus.INVALID,
                                null, null, null,
                                "Capability " + capabilityId
                                        + " has been suspended or retired — fresh start required");
                    }
                }
            } catch (Exception e) {
                log.warn("Could not verify capability availability: {}", e.getMessage());
            }
        }

        // Check 4: Run SELECT/CLARIFY/NO_MATCH on the current fixed candidate set
        // Load the fixed candidate capabilities from the snapshot
        List<CapabilityManifest> candidateManifests = new ArrayList<>();
        try {
            var snapshot = catalogPort.loadSnapshot(interaction.snapshotVersion());
            if (snapshot != null) {
                for (CapabilityManifest manifest : snapshot.capabilities()) {
                    if (interaction.candidateCapabilityIds()
                            .contains(manifest.metadata().id())) {
                        candidateManifests.add(manifest);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load snapshot for interaction: {}", e.getMessage());
            return new ClarificationResult(ClarificationStatus.ERROR,
                    null, null, null,
                    "Failed to load snapshot: " + e.getMessage());
        }

        if (candidateManifests.isEmpty()) {
            interactionRepository.deleteById(interactionId);
            return new ClarificationResult(ClarificationStatus.INVALID,
                    null, null, null,
                    "No candidate capabilities found in snapshot");
        }

        // Construct LLM candidates with aliases
        List<LlmRouterPort.LlmCandidate> llmCandidates = new ArrayList<>();
        Map<String, String> aliasToCapabilityId = new HashMap<>();
        Set<String> existingAliases = new java.util.HashSet<>();

        for (CapabilityManifest manifest : candidateManifests) {
            String alias = aliasGenerator.generate(
                    interaction.snapshotVersion(),
                    manifest.metadata().id(),
                    manifest.metadata().version(),
                    existingAliases);
            existingAliases.add(alias);
            aliasToCapabilityId.put(alias, manifest.metadata().id());

            llmCandidates.add(new LlmRouterPort.LlmCandidate(
                    alias,
                    manifest.spec().displayName(),
                    manifest.spec().description(),
                    manifest.spec().examples().positive(),
                    manifest.spec().examples().negative(),
                    manifest.spec().examples().synonyms(),
                    manifest.spec().inputSchema()
            ));
        }

        // Call the LLM with the fixed candidate set
        ModelDecision decision;
        try {
            decision = llmRouterPort.route(text, llmCandidates);
        } catch (Exception e) {
            log.error("LLM routing failed during clarification: {}", e.getMessage());
            return new ClarificationResult(ClarificationStatus.ERROR,
                    null, null, null,
                    "LLM routing failed: " + e.getMessage());
        }

        // Check 5: Intent breakout detection
        if (decision instanceof ModelDecision.NoMatchDecision) {
            log.info("Intent breakout detected: NO_MATCH during clarification");
            interactionRepository.deleteById(interactionId);
            return new ClarificationResult(ClarificationStatus.INTENT_BREAKOUT,
                    null, null, null,
                    "Intent breakout detected — full pipeline restart required");
        }

        if (decision instanceof ModelDecision.ClarifyDecision clarify) {
            // Update the interaction with the new clarification question
            // The candidate set remains fixed; only the question changes
            log.info("Continuing clarification: interactionId={}", interactionId);
            return new ClarificationResult(ClarificationStatus.CLARIFY,
                    null, clarify.question(), interactionId, null);
        }

        if (decision instanceof ModelDecision.SelectDecision select) {
            // Verify the selected alias is in the original candidate set
            String selectedCapabilityId = aliasToCapabilityId.get(select.alias());
            if (selectedCapabilityId == null) {
                // Intent breakout: selected alias not in original candidates
                log.warn("Intent breakout: selected alias {} not in original candidate set",
                        select.alias());
                interactionRepository.deleteById(interactionId);
                return new ClarificationResult(ClarificationStatus.INTENT_BREAKOUT,
                        null, null, null,
                        "Intent breakout — selected capability not in original candidate set");
            }

            // Find the selected manifest
            CapabilityManifest selectedManifest = candidateManifests.stream()
                    .filter(m -> m.metadata().id().equals(selectedCapabilityId))
                    .findFirst()
                    .orElse(null);

            if (selectedManifest == null) {
                interactionRepository.deleteById(interactionId);
                return new ClarificationResult(ClarificationStatus.ERROR,
                        null, null, null,
                        "Selected capability not found in candidate set");
            }

            // Clarification resolved successfully — clean up the interaction
            interactionRepository.deleteById(interactionId);
            log.info("Clarification resolved: interactionId={}, capability={}",
                    interactionId, selectedCapabilityId);

            Map<String, Object> data = new HashMap<>();
            data.put("capabilityId", selectedManifest.metadata().id());
            data.put("capabilityVersion", selectedManifest.metadata().version());
            data.put("alias", select.alias());
            data.put("modelArguments", select.arguments());
            data.put("snapshotVersion", interaction.snapshotVersion());

            return new ClarificationResult(ClarificationStatus.SELECT,
                    data, null, null, null);
        }

        return new ClarificationResult(ClarificationStatus.ERROR,
                null, null, null,
                "Unknown model decision type: " + decision.getClass().getName());
    }

    /**
     * The status of a clarification continuation.
     */
    public enum ClarificationStatus {
        /** A capability was successfully selected from the fixed candidate set. */
        SELECT,
        /** Further clarification is needed. */
        CLARIFY,
        /** The interaction is invalid (expired, principal changed, etc.). */
        INVALID,
        /** Intent breakout detected — full pipeline restart required. */
        INTENT_BREAKOUT,
        /** An error occurred. */
        ERROR
    }

    /**
     * The result of a clarification continuation.
     *
     * @param status the clarification status
     * @param data the result data; non-null for SELECT
     * @param question the next clarification question; non-null for CLARIFY
     * @param interactionId the interaction ID for continued clarification
     * @param errorMessage the error message; non-null for ERROR/INVALID/INTENT_BREAKOUT
     */
    public record ClarificationResult(
            ClarificationStatus status,
            Map<String, Object> data,
            String question,
            String interactionId,
            String errorMessage
    ) {
    }
}
