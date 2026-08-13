package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.RoutingThresholds;
import com.ai.gateway.domain.port.CandidateRetriever;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Evaluates BM25 retrieval thresholds and ambiguity rules after candidate
 * retrieval.
 *
 * <p>Routing must not rely on the model's self-reported confidence. Instead,
 * the gateway uses thresholds determined from offline labeled sets to decide
 * whether to:</p>
 * <ul>
 * <li><strong>SELECT</strong> — the top-1 candidate's score exceeds the
 * minimum relevance score and exceeds the minimum score gap from
 * top-2.</li>
 * <li><strong>CLARIFY</strong> — multiple candidates have similar scores
 * (within the gap threshold), and the user's request is ambiguous.</li>
 * <li><strong>NO_MATCH</strong> — all candidates are below the minimum
 * relevance score, or the list is empty.</li>
 * </ul>
 *
 * <p>Additional checks:</p>
 * <ul>
 * <li>The user simultaneously expressing multiple actions must trigger
 * clarification.</li>
 * <li>The selected capability's risk level must not exceed the current
 * entry-point's allowed level; if it does, the result is NO_MATCH.</li>
 * </ul>
 *
 * <p>Parameter repair is limited to one retry and must stay with the same
 * selected capability; silently switching to a different interface due to
 * parameter validation failure is prohibited.</p>
 *
 * <p>This class is thread-safe: it holds no mutable state.</p>
 *
 * @see CandidateRetriever.ScoredCapability
 * @see RoutingThresholds
 * @since 0.1.0
 */
public final class ThresholdEvaluator {

    /**
     * The default clarification question used when multiple candidates
     * have similar scores.
     */
    private static final String DEFAULT_CLARIFICATION_QUESTION =
            "Multiple capabilities matched your request. Please clarify which action you want to perform.";

    /**
     * The maximum allowed risk level for the initial release (READ_ONLY only).
     * WRITE_LOW and WRITE_HIGH are not permitted until the two-phase protocol
     * and security review are in place.
     */
    private static final RiskLevel MAX_ALLOWED_RISK = RiskLevel.READ_ONLY;

    /**
     * Evaluates the scored candidates against the routing thresholds and
     * returns a routing decision.
     *
     * <p>The candidates list is expected to be sorted by descending score
     * (as returned by {@link CandidateRetriever#retrieve}). The evaluation
     * applies the following checks in order:</p>
     * <ol>
     * <li>If the list is empty or the top-1 score is below
     * {@code minRelevanceScore}, return {@code NO_MATCH}.</li>
     * <li>If the top-1 candidate's risk level exceeds the allowed level,
     * return {@code NO_MATCH}.</li>
     * <li>If there are two or more candidates and the score gap between
     * top-1 and top-2 is below {@code minTop1Top2ScoreDiff}, return
     * {@code CLARIFY}.</li>
     * <li>Otherwise, return {@code SELECT} with the top-1 candidate.</li>
     * </ol>
     *
     * @param candidates the scored candidates sorted by descending score;
     * may be empty but not null
     * @param thresholds the routing thresholds; must not be null
     * @return the threshold evaluation result; never null
     * @throws NullPointerException if {@code candidates} or {@code thresholds} is null
     */
    public ThresholdResult evaluate(List<CandidateRetriever.ScoredCapability> candidates,
                                    RoutingThresholds thresholds) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(thresholds, "thresholds must not be null");

        // Check 1: empty list or all below threshold -> NO_MATCH
        if (candidates.isEmpty()) {
            return ThresholdResult.noMatch("No candidates retrieved.");
        }

        CandidateRetriever.ScoredCapability top1 = candidates.get(0);

        // Check 2: top-1 score below minimum relevance -> NO_MATCH
        if (top1.score() < thresholds.minRelevanceScore()) {
            return ThresholdResult.noMatch(
                    "Top candidate score " + top1.score()
                            + " is below minimum relevance " + thresholds.minRelevanceScore()
            );
        }

        // Check 3: risk level exceeds allowed level -> NO_MATCH
        CapabilityManifest manifest = top1.capability();
        RiskLevel risk = manifest.spec().risk();
        if (exceedsAllowedRisk(risk)) {
            return ThresholdResult.noMatch(
                    "Capability " + manifest.metadata().id()
                            + " risk level " + risk + " exceeds the current entry-point allowed level "
                            + MAX_ALLOWED_RISK
            );
        }

        // Check 4: multiple candidates with similar scores -> CLARIFY
        if (candidates.size() >= 2) {
            CandidateRetriever.ScoredCapability top2 = candidates.get(1);
            double scoreGap = top1.score() - top2.score();
            if (scoreGap < thresholds.minTop1Top2ScoreDiff()) {
                return ThresholdResult.clarify(
                        candidates,
                        DEFAULT_CLARIFICATION_QUESTION
                );
            }
        }

        // Check 5: clear winner -> SELECT
        return ThresholdResult.select(top1);
    }

    /**
     * Checks whether the given risk level exceeds the maximum allowed level
     * for the current entry point.
     *
     * <p>In the initial release, only {@code READ_ONLY} capabilities are
     * permitted. {@code WRITE_LOW} requires the two-phase Prepare/Confirm
     * protocol (Section 13), and {@code WRITE_HIGH} requires independent
     * security review and dual approval.</p>
     *
     * @param risk the risk level to check
     * @return {@code true} if the risk level exceeds the allowed level
     */
    private boolean exceedsAllowedRisk(RiskLevel risk) {
        return risk != MAX_ALLOWED_RISK;
    }

    /**
     * The result of threshold evaluation, containing the routing decision
     * and associated data.
     *
     * <p>Depending on the {@link Decision}, the result carries:</p>
     * <ul>
     * <li>{@code SELECT} — the selected candidate.</li>
     * <li>{@code CLARIFY} — the ambiguous candidates and a clarification
     * question.</li>
     * <li>{@code NO_MATCH} — a reason string.</li>
     * </ul>
     *
     * @param decision the routing decision
     * @param selectedCandidate the selected candidate for SELECT; empty otherwise
     * @param clarificationCandidates the ambiguous candidates for CLARIFY; empty otherwise
     * @param clarificationQuestion the clarification question for CLARIFY; empty otherwise
     * @param noMatchReason the reason for NO_MATCH; empty otherwise
     */
    public record ThresholdResult(
            Decision decision,
            Optional<CandidateRetriever.ScoredCapability> selectedCandidate,
            List<CandidateRetriever.ScoredCapability> clarificationCandidates,
            String clarificationQuestion,
            String noMatchReason
    ) {
        /**
         * Compact constructor performing defensive copying.
         *
         * @param decision the decision
         * @param selectedCandidate the selected candidate
         * @param clarificationCandidates the clarification candidates
         * @param clarificationQuestion the clarification question
         * @param noMatchReason the no-match reason
         */
        public ThresholdResult {
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(selectedCandidate, "selectedCandidate must not be null");
            Objects.requireNonNull(clarificationCandidates, "clarificationCandidates must not be null");
            clarificationCandidates = List.copyOf(clarificationCandidates);
            selectedCandidate = selectedCandidate == null ? Optional.empty() : selectedCandidate;
            clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
            noMatchReason = noMatchReason == null ? "" : noMatchReason;
        }

        /**
         * Creates a SELECT result.
         *
         * @param candidate the selected candidate
         * @return a SELECT ThresholdResult
         */
        static ThresholdResult select(CandidateRetriever.ScoredCapability candidate) {
            return new ThresholdResult(
                    Decision.SELECT,
                    Optional.of(candidate),
                    List.of(),
                    "",
                    ""
            );
        }

        /**
         * Creates a CLARIFY result.
         *
         * @param candidates the ambiguous candidates
         * @param question the clarification question
         * @return a CLARIFY ThresholdResult
         */
        static ThresholdResult clarify(List<CandidateRetriever.ScoredCapability> candidates,
                                       String question) {
            return new ThresholdResult(
                    Decision.CLARIFY,
                    Optional.empty(),
                    candidates,
                    question,
                    ""
            );
        }

        /**
         * Creates a NO_MATCH result.
         *
         * @param reason the reason for no match
         * @return a NO_MATCH ThresholdResult
         */
        static ThresholdResult noMatch(String reason) {
            return new ThresholdResult(
                    Decision.NO_MATCH,
                    Optional.empty(),
                    List.of(),
                    "",
                    reason
            );
        }
    }

    /**
     * The routing decision determined by threshold evaluation.
     *
     * @see ThresholdResult
     * @since 0.1.0
     */
    public enum Decision {
        /**
         * A single clear winner was identified above all thresholds.
         * The gateway proceeds to parameter binding and execution.
         */
        SELECT,

        /**
         * Multiple candidates have similar scores, or the request is
         * ambiguous. The gateway must ask the user to clarify.
         */
        CLARIFY,

        /**
         * No candidate met the minimum relevance score, or the list
         * was empty. The gateway returns a no-match response.
         */
        NO_MATCH
    }
}
