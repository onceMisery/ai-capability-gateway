package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.OperationState;

import java.util.Map;
import java.util.Set;

/**
 * Enforces the write operation state machine for the two-phase
 * Prepare/Confirm protocol defined in of the design document.
 *
 * <p>The canonical state graph is:</p>
 * <pre>
 * PREPARED -&gt; EXECUTING -&gt; SUCCEEDED
 * | |----&gt; FAILED
 * | +----&gt; UNKNOWN -&gt; SUCCEEDED / FAILED / MANUAL_REVIEW
 * |-----------------&gt; EXPIRED
 * +-----------------&gt; CANCELLED
 * </pre>
 *
 * <p>Key rules:</p>
 * <ul>
 * <li>{@code UNKNOWN} means the request may have reached the Provider, but
 * the gateway did not receive a definitive response.</li>
 * <li>{@code UNKNOWN} must not auto-retry the natural-language request.
 * Recovery tasks use the idempotency key to query the Provider status.</li>
 * <li>Unresolvable cases enter {@code MANUAL_REVIEW} and raise an alert.</li>
 * <li>{@code SUCCEEDED}, {@code FAILED}, {@code EXPIRED},
 * {@code CANCELLED}, and {@code MANUAL_REVIEW} are terminal states.</li>
 * </ul>
 *
 * <p>This class is thread-safe: it holds only immutable static data and
 * performs no mutation.</p>
 *
 * @see OperationState
 * @since 0.1.0
 */
public final class OperationStateMachine {

    /**
     * Immutable map of allowed transitions from each source state.
     *
     * <p>Keys not present in the map (SUCCEEDED, FAILED, EXPIRED, CANCELLED,
     * MANUAL_REVIEW) are terminal and have no allowed outgoing transitions.</p>
     */
    private static final Map<OperationState, Set<OperationState>> TRANSITIONS = Map.of(
            OperationState.PREPARED, Set.of(
                    OperationState.EXECUTING,
                    OperationState.EXPIRED,
                    OperationState.CANCELLED
            ),
            OperationState.EXECUTING, Set.of(
                    OperationState.SUCCEEDED,
                    OperationState.FAILED,
                    OperationState.UNKNOWN
            ),
            OperationState.UNKNOWN, Set.of(
                    OperationState.SUCCEEDED,
                    OperationState.FAILED,
                    OperationState.MANUAL_REVIEW
            )
    );

    /**
     * The set of terminal states that have no outgoing transitions.
     */
    private static final Set<OperationState> TERMINAL_STATES = Set.of(
            OperationState.SUCCEEDED,
            OperationState.FAILED,
            OperationState.EXPIRED,
            OperationState.CANCELLED,
            OperationState.MANUAL_REVIEW
    );

    /**
     * Checks whether a transition from one operation state to another is
     * allowed by the state machine rules.
     *
     * <p>Self-transitions (e.g., PREPARED to PREPARED) are not allowed.
     * Terminal states have no outgoing transitions.</p>
     *
     * @param from the current operation state
     * @param to the target operation state
     * @return {@code true} if the transition is permitted; {@code false} otherwise
     * @throws NullPointerException if {@code from} or {@code to} is null
     */
    public boolean canTransition(OperationState from, OperationState to) {
        java.util.Objects.requireNonNull(from, "from must not be null");
        java.util.Objects.requireNonNull(to, "to must not be null");
        if (from == to) {
            return false;
        }
        Set<OperationState> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Validates an operation state transition, throwing an exception if the
     * transition is not permitted by the state machine rules.
     *
     * <p>The exception message describes the invalid transition and lists
     * the allowed target states (if any).</p>
     *
     * @param from the current operation state
     * @param to the target operation state
     * @throws IllegalArgumentException if the transition is not permitted
     * @throws NullPointerException if {@code from} or {@code to} is null
     */
    public void validateTransition(OperationState from, OperationState to) {
        if (!canTransition(from, to)) {
            if (TERMINAL_STATES.contains(from)) {
                throw new IllegalArgumentException(
                        "Illegal operation state transition: " + from + " is a terminal state; "
                                + "no further transitions are permitted"
                );
            }
            Set<OperationState> allowed = TRANSITIONS.get(from);
            if (allowed == null || allowed.isEmpty()) {
                throw new IllegalArgumentException(
                        "Illegal operation state transition from " + from + " to " + to
                                + "; no outgoing transitions are defined for " + from
                );
            }
            throw new IllegalArgumentException(
                    "Illegal operation state transition from " + from + " to " + to
                            + "; allowed transitions from " + from + " are: " + allowed
            );
        }
    }

    /**
     * Returns the set of states reachable from the given source state.
     *
     * @param from the source state
     * @return an unmodifiable set of allowed target states; empty if the
     * source state is terminal
     * @throws NullPointerException if {@code from} is null
     */
    public Set<OperationState> allowedTransitions(OperationState from) {
        java.util.Objects.requireNonNull(from, "from must not be null");
        Set<OperationState> allowed = TRANSITIONS.get(from);
        return allowed != null ? Set.copyOf(allowed) : Set.of();
    }

    /**
     * Returns whether the given state is terminal (no outgoing transitions).
     *
     * @param state the state to check
     * @return {@code true} if the state is terminal; {@code false} otherwise
     * @throws NullPointerException if {@code state} is null
     */
    public boolean isTerminal(OperationState state) {
        java.util.Objects.requireNonNull(state, "state must not be null");
        return TERMINAL_STATES.contains(state);
    }

    /**
     * Returns whether the {@code UNKNOWN} state prohibits automatic retry
     * of the natural-language request.
     *
     * <p>When an operation is in {@code UNKNOWN}, the gateway must not
     * re-execute the request. Instead, a recovery task uses the idempotency
     * key to query the Provider status. This method serves as a guard
     * for the caller to enforce this rule.</p>
     *
     * @param state the operation state to check
     * @return {@code true} if the state is {@code UNKNOWN} and auto-retry
     * is prohibited; {@code false} otherwise
     * @throws NullPointerException if {@code state} is null
     */
    public boolean prohibitsAutoRetry(OperationState state) {
        java.util.Objects.requireNonNull(state, "state must not be null");
        return state == OperationState.UNKNOWN;
    }
}
