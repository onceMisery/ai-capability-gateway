package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.CapabilityManifest;

import java.util.List;

/**
 * Port for BM25-based candidate retrieval during natural-language routing.
 *
 * <p>(Candidate Retrieval) specifies that the BM25 index
 * includes:</p>
 * <ul>
 * <li>{@code displayName} — the user-facing capability name.</li>
 * <li>Business action description.</li>
 * <li>Positive, negative, and synonym examples.</li>
 * <li>Domain and controlled tags.</li>
 * <li>Public field names and business descriptions.</li>
 * </ul>
 *
 * <p>The physical index must not contain protocol addresses, internal
 * interface details, or secrets. It may contain all published capabilities
 * in the environment. Every retrieval must apply non-bypassable
 * authorization filtering within the retrieval engine so that
 * capabilities the current Principal is not authorized for do not
 * participate in scoring and Top-K truncation. If the engine cannot
 * safely filter at query time, the authorized subset must be built first
 * before retrieval — the gateway must not take a global Top-K and then
 * intersect.</p>
 *
 * <p>Chinese retrieval must use a fixed tokenizer, dictionary, and
 * synonym version, recorded in the snapshot and evaluation report, to
 * avoid unexplainable routing differences across instances or before and
 * after publication.</p>
 *
 * <p>The initial release uses lexical retrieval to avoid making a vector
 * database a launch prerequisite. Vector or hybrid retrieval may only be
 * introduced after offline evaluation proves significant Recall@K
 * improvement with acceptable data governance, cost, and failure modes.</p>
 *
 * <p>Adapters implementing this port build and query the BM25 index.
 * The port is a pure abstraction with no framework dependencies.</p>
 *
 * @see CapabilityManifest
 * @see ScoredCapability
 * @since 0.1.0
 */
public interface CandidateRetriever {

    /** Returns the catalog version backing the current index, or -1 when unknown. */
    default long indexedCatalogVersion() {
        return -1L;
    }

    /**
     * Retrieves the Top-K scored capabilities matching the normalized
     * user text from the authorized capability set.
     *
     * <p>: authorization filtering is non-bypassable.
     * Capabilities the current Principal is not authorized for do not
     * participate in scoring or Top-K truncation. The
     * {@code authorizedCapabilities} parameter is the pre-filtered set.</p>
     *
     * <p>: the gateway applies threshold checks after retrieval
     * — minimum relevance score, minimum Top-1 vs Top-2 score gap, and
     * maximum candidate count. The retriever only returns scored
     * candidates; the gateway makes the final routing decision.</p>
     *
     * @param normalizedText the normalized user natural-language text
     * @param authorizedCapabilities the pre-authorized capability set to
     * search within
     * @param topK the maximum number of candidates to return
     * @return the list of scored capabilities, sorted by descending score;
     * never {@code null}
     */
    List<ScoredCapability> retrieve(String normalizedText,
                                    List<CapabilityManifest> authorizedCapabilities,
                                    int topK);

    /**
     * A capability with its BM25 relevance score.
     *
     * <p>: the score is the BM25 relevance score computed by
     * the retrieval engine. specifies that the gateway must
     * not rely on the model's self-reported confidence; instead, it uses
     * thresholds determined from offline labeled sets.</p>
     *
     * @param capability the matched capability manifest
     * @param score the BM25 relevance score
     */
    record ScoredCapability(CapabilityManifest capability, double score) {
    }
}
