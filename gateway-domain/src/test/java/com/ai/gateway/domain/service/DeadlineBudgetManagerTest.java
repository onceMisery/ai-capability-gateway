package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.DeadlineBudget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DeadlineBudgetManagerTest {
    private final DeadlineBudgetManager manager = new DeadlineBudgetManager();

    @Test
    void shouldCreateBudgetWithFullRemaining() {
        DeadlineBudget budget = manager.createBudget(15000);
        assertThat(budget.totalDeadlineMs()).isEqualTo(15000);
        assertThat(budget.remainingMs()).isEqualTo(15000);
        assertThat(budget.isExpired()).isFalse();
    }

    @Test
    void shouldReduceRemainingOnSpend() {
        DeadlineBudget budget = manager.createBudget(15000);
        DeadlineBudget reduced = budget.spend(5000);
        assertThat(reduced.remainingMs()).isEqualTo(10000);
        assertThat(reduced.totalDeadlineMs()).isEqualTo(15000);
    }

    @Test
    void shouldDetectExpiredBudget() {
        DeadlineBudget budget = new DeadlineBudget(15000, 0);
        assertThat(budget.isExpired()).isTrue();
    }

    @Test
    void shouldNotGoBelowZero() {
        DeadlineBudget budget = new DeadlineBudget(15000, 1000);
        DeadlineBudget reduced = budget.spend(5000);
        assertThat(reduced.remainingMs()).isGreaterThanOrEqualTo(0);
        assertThat(reduced.remainingMs()).isEqualTo(0);
    }

    @Test
    void shouldAllocatePhaseBudget() {
        DeadlineBudget budget = manager.createBudget(15000);
        DeadlineBudget afterPhase = manager.allocatePhase(budget, 3000);
        assertThat(afterPhase.remainingMs()).isEqualTo(12000);
    }

    @Test
    void shouldCapPhaseBudgetAtRemaining() {
        DeadlineBudget budget = new DeadlineBudget(15000, 2000);
        DeadlineBudget afterPhase = manager.allocatePhase(budget, 5000);
        assertThat(afterPhase.remainingMs()).isEqualTo(0);
    }

    @Test
    void shouldThrowOnNonPositiveTotalDeadline() {
        assertThatThrownBy(() -> manager.createBudget(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.createBudget(-100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowOnNegativePhaseBudget() {
        DeadlineBudget budget = manager.createBudget(15000);
        assertThatThrownBy(() -> manager.allocatePhase(budget, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowOnNegativeSpend() {
        DeadlineBudget budget = manager.createBudget(15000);
        assertThatThrownBy(() -> budget.spend(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCalculateRemainingForProvider() {
        DeadlineBudget budget = manager.createBudget(15000);
        long providerRemaining = manager.remainingForProvider(budget);
        // governance reserve = 15000 * 0.10 = 1500
        // provider remaining = 15000 - 1500 = 13500
        assertThat(providerRemaining).isEqualTo(13500);
    }

    @Test
    void shouldReturnZeroProviderRemainingWhenExpired() {
        DeadlineBudget budget = new DeadlineBudget(15000, 0);
        assertThat(manager.remainingForProvider(budget)).isEqualTo(0);
    }

    @Test
    void shouldReturnRecommendedPhaseBudgets() {
        long total = 10000;
        assertThat(manager.recommendedAuthPhaseBudget(total)).isEqualTo(500);
        assertThat(manager.recommendedRetrievalPhaseBudget(total)).isEqualTo(1000);
        assertThat(manager.recommendedLlmRoutingPhaseBudget(total)).isEqualTo(3000);
        assertThat(manager.recommendedValidationPhaseBudget(total)).isEqualTo(500);
        assertThat(manager.recommendedProviderPhaseBudget(total)).isEqualTo(4000);
        assertThat(manager.recommendedResultGovernancePhaseBudget(total)).isEqualTo(1000);
    }
}
