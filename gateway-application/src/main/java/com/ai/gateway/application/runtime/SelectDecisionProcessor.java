package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.CandidateRetriever;

import java.util.List;

/**
 * Extension point for processing a threshold evaluator SELECT decision.
 */
public interface SelectDecisionProcessor {

    NaturalLanguageQueryUseCase.QueryResult process(
            CandidateRetriever.ScoredCapability selected,
            Principal principal,
            long snapshotVersion,
            String requestId,
            String originalText,
            String locale,
            long startTime,
            TerminalRecorder terminalRecorder);

    @FunctionalInterface
    interface TerminalRecorder {
        NaturalLanguageQueryUseCase.QueryResult record(
                NaturalLanguageQueryUseCase.QueryResult result,
                Principal principal,
                String requestId,
                long snapshotVersion,
                CapabilityManifest manifest,
                long startTime);

        default NaturalLanguageQueryUseCase.QueryResult clarification(
                List<CandidateRetriever.ScoredCapability> candidates,
                String question,
                Principal principal,
                long snapshotVersion,
                String requestId,
                long startTime) {
            throw new UnsupportedOperationException("Clarification handler is required");
        }
    }
}
