package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.RoutingThresholds;
import com.ai.gateway.domain.port.CandidateRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ThresholdEvaluatorTest {
    private final ThresholdEvaluator evaluator = new ThresholdEvaluator();
    private final RoutingThresholds thresholds = new RoutingThresholds(0.5, 0.2, 5, 4096);

    @Test
    void shouldReturnNoMatchForEmptyCandidates() {
        ThresholdEvaluator.ThresholdResult result = evaluator.evaluate(List.of(), thresholds);
        assertThat(result.decision()).isEqualTo(ThresholdEvaluator.Decision.NO_MATCH);
        assertThat(result.noMatchReason()).isNotEmpty();
        assertThat(result.selectedCandidate()).isEmpty();
    }

    @Test
    void shouldThrowOnNullCandidates() {
        assertThatThrownBy(() -> evaluator.evaluate(null, thresholds))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowOnNullThresholds() {
        assertThatThrownBy(() -> evaluator.evaluate(List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveCorrectThresholdValues() {
        assertThat(thresholds.minRelevanceScore()).isEqualTo(0.5);
        assertThat(thresholds.minTop1Top2ScoreDiff()).isEqualTo(0.2);
        assertThat(thresholds.maxCandidates()).isEqualTo(5);
        assertThat(thresholds.maxTokenBudget()).isEqualTo(4096);
    }

    @Test
    void shouldVerifyEvaluatorIsInstantiable() {
        assertThat(evaluator).isNotNull();
    }
}
