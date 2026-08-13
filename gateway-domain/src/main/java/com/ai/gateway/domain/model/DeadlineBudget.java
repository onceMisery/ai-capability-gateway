package com.ai.gateway.domain.model;

/**
 * Immutable deadline budget tracker for request execution.
 *
 * <p>Specifies that the entry-point deadline must be divided
 * among pipeline stages (authentication, retrieval, LLM routing, validation,
 * Provider call, result governance). No downstream timeout may exceed the
 * remaining time at the point of the call.</p>
 *
 * <p>Each stage that consumes time calls {@link #spend(long)} to produce a
 * new {@code DeadlineBudget} with the reduced remaining time. The original
 * instance is never mutated. A budget with {@code remainingMs <= 0} is
 * considered expired.</p>
 *
 * <p>Client disconnect does not equal write-operation failure; write
 * operations must be resolved via the status API.</p>
 *
 * @param totalDeadlineMs the original total deadline in milliseconds
 * @param remainingMs the remaining milliseconds before the deadline
 * @since 0.1.0
 */
public record DeadlineBudget(long totalDeadlineMs, long remainingMs) {

    /**
     * Returns a new budget with the specified milliseconds deducted from
     * the remaining time.
     *
     * @param ms the milliseconds spent by the current stage; must be
     * non-negative
     * @return a new {@code DeadlineBudget} with reduced remaining time
     * @throws IllegalArgumentException if {@code ms} is negative
     */
    public DeadlineBudget spend(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("spent time must not be negative: " + ms);
        }
        long newRemaining = Math.max(0, remainingMs - ms);
        return new DeadlineBudget(totalDeadlineMs, newRemaining);
    }

    /**
     * Returns whether the deadline has been exhausted.
     *
     * @return {@code true} if {@code remainingMs <= 0}
     */
    public boolean isExpired() {
        return remainingMs <= 0;
    }
}
