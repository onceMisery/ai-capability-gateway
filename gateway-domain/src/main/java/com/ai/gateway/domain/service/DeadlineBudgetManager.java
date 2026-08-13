package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.DeadlineBudget;

/**
 * Manages the deadline budget allocation across pipeline stages
 *
 * <p>The entry-point deadline must be divided among pipeline stages so that
 * no downstream timeout exceeds the remaining time at the point of the call.
 * The stages are:</p>
 * <pre>
 * Total Deadline
 * - Authentication / Authorization budget
 * - Retrieval budget
 * - LLM routing budget
 * - Pre-execution validation budget
 * - Provider call budget
 * - Result governance budget
 * </pre>
 *
 * <p>Each stage that consumes time calls {@link DeadlineBudget#spend(long)}
 * to produce a new {@code DeadlineBudget} with the reduced remaining time.
 * The original instance is never mutated.</p>
 *
 * <p>The {@link #allocatePhase(DeadlineBudget, long)} method deducts the phase
 * budget from the remaining time, but caps the deduction at the remaining
 * time — this ensures no stage's budget exceeds what is left. The
 * {@link #remainingForProvider(DeadlineBudget)} method computes the safe
 * timeout for the Provider call, which must not exceed the entrance
 * remaining time.</p>
 *
 * <p>Client disconnect does not equal write-operation failure; write
 * operations must be resolved via the status API.</p>
 *
 * <p>This class is thread-safe: it holds no mutable state.</p>
 *
 * @see DeadlineBudget
 * @since 0.1.0
 */
public final class DeadlineBudgetManager {

    /**
     * Default budget allocation percentages for each pipeline stage.
     *
     * <p>These are recommended defaults; callers may override by passing
     * explicit phase budgets to {@link #allocatePhase}. The sum of all
     * stage percentages is 100%.</p>
     */
    private static final double AUTH_PHASE_RATIO = 0.05;
    private static final double RETRIEVAL_PHASE_RATIO = 0.10;
    private static final double LLM_ROUTING_PHASE_RATIO = 0.30;
    private static final double VALIDATION_PHASE_RATIO = 0.05;
    private static final double PROVIDER_PHASE_RATIO = 0.40;
    private static final double RESULT_GOVERNANCE_PHASE_RATIO = 0.10;

    /**
     * Creates a new deadline budget for the given total deadline
     *
     * @param totalDeadlineMs the total request deadline in milliseconds;
     * must be positive
     * @return a new {@link DeadlineBudget} with full remaining time
     * @throws IllegalArgumentException if {@code totalDeadlineMs <= 0}
     */
    public DeadlineBudget createBudget(long totalDeadlineMs) {
        if (totalDeadlineMs <= 0) {
            throw new IllegalArgumentException(
                    "totalDeadlineMs must be positive: " + totalDeadlineMs
            );
        }
        return new DeadlineBudget(totalDeadlineMs, totalDeadlineMs);
    }

    /**
     * Allocates a phase budget from the current remaining time and returns
     * a reduced budget.
     *
     * <p>The phase budget is capped at the remaining time to ensure no
     * stage's budget exceeds what is left. The returned budget has the
     * phase budget deducted from its remaining time.</p>
     *
     * @param budget the current deadline budget; must not be null
     * @param phaseBudgetMs the time budgeted for this phase; if this exceeds
     * the remaining time, the remaining time is used
     * instead
     * @return a new {@code DeadlineBudget} with the phase budget deducted;
     * never exceeds the remaining time of the input budget
     * @throws NullPointerException if {@code budget} is null
     * @throws IllegalArgumentException if {@code phaseBudgetMs} is negative
     */
    public DeadlineBudget allocatePhase(DeadlineBudget budget, long phaseBudgetMs) {
        java.util.Objects.requireNonNull(budget, "budget must not be null");
        if (phaseBudgetMs < 0) {
            throw new IllegalArgumentException(
                    "phaseBudgetMs must not be negative: " + phaseBudgetMs
            );
        }

        long actualPhaseBudget = Math.min(phaseBudgetMs, budget.remainingMs());
        return budget.spend(actualPhaseBudget);
    }

    /**
     * Calculates the safe remaining time for the Provider call
     *
     * <p>The Provider call timeout must not exceed the entrance remaining
     * time. This method returns the remaining time after deducting a
     * reserve for the result governance phase. If the remaining time is
     * already exhausted, it returns 0.</p>
     *
     * <p>The reserve for result governance is computed using the default
     * ratio ({@value #RESULT_GOVERNANCE_PHASE_RATIO} of the total deadline).
     * If the remaining time is less than this reserve, the entire remaining
     * time is returned (the Provider gets whatever is left).</p>
     *
     * @param budget the current deadline budget; must not be null
     * @return the safe Provider call timeout in milliseconds; never negative
     * @throws NullPointerException if {@code budget} is null
     */
    public long remainingForProvider(DeadlineBudget budget) {
        java.util.Objects.requireNonNull(budget, "budget must not be null");

        if (budget.isExpired()) {
            return 0;
        }

        long governanceReserve = (long) (budget.totalDeadlineMs() * RESULT_GOVERNANCE_PHASE_RATIO);
        long remaining = budget.remainingMs();

        if (remaining <= governanceReserve) {
            // Not enough time for both Provider and governance; give it all
            // to the Provider (the governance phase may run in overtime,
            // but the Provider must still be called).
            return remaining;
        }

        return remaining - governanceReserve;
    }

    /**
     * Returns the recommended budget for the authentication/authorization
     * phase.
     *
     * @param totalDeadlineMs the total request deadline
     * @return the recommended phase budget in milliseconds
     */
    public long recommendedAuthPhaseBudget(long totalDeadlineMs) {
        return (long) (totalDeadlineMs * AUTH_PHASE_RATIO);
    }

    /**
     * Returns the recommended budget for the retrieval phase.
     *
     * @param totalDeadlineMs the total request deadline
     * @return the recommended phase budget in milliseconds
     */
    public long recommendedRetrievalPhaseBudget(long totalDeadlineMs) {
        return (long) (totalDeadlineMs * RETRIEVAL_PHASE_RATIO);
    }

    /**
     * Returns the recommended budget for the LLM routing phase.
     *
     * @param totalDeadlineMs the total request deadline
     * @return the recommended phase budget in milliseconds
     */
    public long recommendedLlmRoutingPhaseBudget(long totalDeadlineMs) {
        return (long) (totalDeadlineMs * LLM_ROUTING_PHASE_RATIO);
    }

    /**
     * Returns the recommended budget for the pre-execution validation phase
     *
     * @param totalDeadlineMs the total request deadline
     * @return the recommended phase budget in milliseconds
     */
    public long recommendedValidationPhaseBudget(long totalDeadlineMs) {
        return (long) (totalDeadlineMs * VALIDATION_PHASE_RATIO);
    }

    /**
     * Returns the recommended budget for the Provider call phase
     *
     * @param totalDeadlineMs the total request deadline
     * @return the recommended phase budget in milliseconds
     */
    public long recommendedProviderPhaseBudget(long totalDeadlineMs) {
        return (long) (totalDeadlineMs * PROVIDER_PHASE_RATIO);
    }

    /**
     * Returns the recommended budget for the result governance phase
     *
     * @param totalDeadlineMs the total request deadline
     * @return the recommended phase budget in milliseconds
     */
    public long recommendedResultGovernancePhaseBudget(long totalDeadlineMs) {
        return (long) (totalDeadlineMs * RESULT_GOVERNANCE_PHASE_RATIO);
    }
}
