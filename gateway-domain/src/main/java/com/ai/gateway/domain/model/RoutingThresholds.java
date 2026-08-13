package com.ai.gateway.domain.model;

/**
 * Thresholds for candidate retrieval and ambiguity resolution in the
 * natural-language routing pipeline.
 *
 * <p>Specifies that routing must not rely on model self-reported
 * confidence. Instead, the gateway uses offline-annotated benchmarks to
 * determine the following thresholds:</p>
 *
 * <ul>
 * <li>{@code minRelevanceScore} - minimum BM25 relevance score for a
 * candidate to be included in the Top-K set.</li>
 * <li>{@code minTop1Top2ScoreDiff} - minimum score difference between the
 * top-1 and top-2 candidates; a smaller difference signals ambiguity.</li>
 * <li>{@code maxCandidates} - maximum number of candidates passed to the
 * LLM for selection (Top-K).</li>
 * <li>{@code maxTokenBudget} - maximum token budget for the LLM prompt
 * containing candidate descriptions and input Schemas.</li>
 * </ul>
 *
 * <p>The gateway must request clarification or return {@code NO_MATCH} when
 * any of the following occur:</p>
 * <ul>
 * <li>Retrieval score below threshold.</li>
 * <li>Multiple candidates express different business actions and cannot
 * be distinguished from the request.</li>
 * <li>Required parameters are missing.</li>
 * <li>The user simultaneously expresses multiple actions.</li>
 * <li>The user's requested action risk exceeds the current entry-point
 * allowed level.</li>
 * <li>The model selects outside the candidate set, outputs unparseable
 * results, or is inconsistent across retries.</li>
 * </ul>
 *
 * @param minRelevanceScore the minimum BM25 score for candidate inclusion
 * @param minTop1Top2ScoreDiff the minimum top-1/top-2 score gap
 * @param maxCandidates the maximum Top-K candidates passed to the LLM
 * @param maxTokenBudget the maximum token budget for the LLM prompt
 * @since 0.1.0
 */
public record RoutingThresholds(
        double minRelevanceScore,
        double minTop1Top2ScoreDiff,
        int maxCandidates,
        int maxTokenBudget
) {
}
