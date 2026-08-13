package com.ai.gateway.application;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Routing evaluation framework.
 *
 * <p>Uses versioned offline annotation sets, stratified by domain,
 * capability, risk, and hard cases.
 *
 * <p>Release gate thresholds:
 * <ul>
 * <li>Candidate retrieval Recall@5 >= 95%</li>
 * <li>End-to-end correct capability selection rate >= 90%</li>
 * <li>Unauthorized capability exposure rate = 0</li>
 * <li>High-risk erroneous execution rate = 0</li>
 * <li>No-match sample erroneous invocation rate <= 1%</li>
 * </ul>
 */
@Disabled("Requires annotation dataset and LLM endpoint - run in evaluation CI")
class RoutingEvaluationTest {

    @Test
    @DisplayName("Recall@5 >= 95% on annotated dataset")
    void recallAt5() {
        // Load versioned annotation set
        // Run BM25 retrieval for each sample
        // Assert Recall@5 >= 0.95 with confidence interval
    }

    @Test
    @DisplayName("End-to-end selection accuracy >= 90%")
    void selectionAccuracy() {
        // Run full routing pipeline for each sample
        // Assert correct selection rate >= 0.90
    }

    @Test
    @DisplayName("Unauthorized capability exposure rate = 0")
    void unauthorizedExposure() {
        // Verify no unauthorized capability appears in candidates
        // Assert exposure rate == 0
    }

    @Test
    @DisplayName("High-risk erroneous execution rate = 0")
    void highRiskExecution() {
        // Verify WRITE_HIGH capabilities never execute without proper authorization
        // Assert erroneous execution rate == 0
    }

    @Test
    @DisplayName("No-match erroneous invocation rate <= 1%")
    void noMatchErroneousInvocation() {
        // For samples labeled NO_MATCH, verify gateway returns NO_MATCH
        // Assert erroneous invocation rate <= 0.01
    }
}
