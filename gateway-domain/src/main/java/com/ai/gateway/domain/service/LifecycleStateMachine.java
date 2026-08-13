package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.CapabilityLifecycle;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Enforces the Capability Manifest lifecycle state machine defined in
 * of the design document.
 *
 * <p>The canonical state graph is:</p>
 * <pre>
 * DRAFT -&gt; VALIDATED -&gt; APPROVED -&gt; PUBLISHED -&gt; SUSPENDED -&gt; RETIRED
 * | | |
 * +--------&gt; REJECTED &lt--+
 * </pre>
 *
 * <p>Only {@link CapabilityLifecycle#PUBLISHED} capabilities may enter the
 * candidate set for natural-language routing (Section 9). Write operations
 * must be rejected if the capability is {@code SUSPENDED} or {@code RETIRED}
 * at confirm time.</p>
 *
 * <p>Restoration of a {@code SUSPENDED} capability requires re-validation and
 * a new snapshot; it must not be done in-place on the original snapshot
 *. The transition from {@code SUSPENDED} back to
 * {@code PUBLISHED} is therefore allowed only after re-validation has
 * succeeded — the caller is responsible for ensuring that precondition.</p>
 *
 * <p>{@code RETIRED} and {@code REJECTED} are terminal states: no further
 * transitions are permitted.</p>
 *
 * <p>This class is thread-safe: it holds only immutable static data and
 * performs no mutation.</p>
 *
 * @see CapabilityLifecycle
 * @since 0.1.0
 */
public final class LifecycleStateMachine {

    /**
     * Immutable map of allowed transitions from each source state.
     *
     * <p>Keys not present in the map (REJECTED, RETIRED) are terminal and
     * have no allowed outgoing transitions.</p>
     */
    private static final Map<CapabilityLifecycle, Set<CapabilityLifecycle>> TRANSITIONS = Map.of(
            CapabilityLifecycle.DRAFT, Set.of(
                    CapabilityLifecycle.VALIDATED,
                    CapabilityLifecycle.REJECTED
            ),
            CapabilityLifecycle.VALIDATED, Set.of(
                    CapabilityLifecycle.APPROVED,
                    CapabilityLifecycle.REJECTED
            ),
            CapabilityLifecycle.APPROVED, Set.of(
                    CapabilityLifecycle.PUBLISHED,
                    CapabilityLifecycle.REJECTED
            ),
            CapabilityLifecycle.PUBLISHED, Set.of(
                    CapabilityLifecycle.SUSPENDED,
                    CapabilityLifecycle.RETIRED
            ),
            CapabilityLifecycle.SUSPENDED, Set.of(
                    CapabilityLifecycle.PUBLISHED,
                    CapabilityLifecycle.RETIRED
            )
    );

    /**
     * The set of terminal states that have no outgoing transitions.
     */
    private static final Set<CapabilityLifecycle> TERMINAL_STATES = EnumSet.of(
            CapabilityLifecycle.RETIRED,
            CapabilityLifecycle.REJECTED
    );

    /**
     * Checks whether a transition from one lifecycle state to another is
     * allowed by the state machine rules.
     *
     * <p>Self-transitions (e.g., DRAFT to DRAFT) are not allowed. Terminal
     * states (RETIRED, REJECTED) have no outgoing transitions.</p>
     *
     * @param from the current lifecycle state
     * @param to the target lifecycle state
     * @return {@code true} if the transition is permitted; {@code false} otherwise
     * @throws NullPointerException if {@code from} or {@code to} is null
     */
    public boolean canTransition(CapabilityLifecycle from, CapabilityLifecycle to) {
        java.util.Objects.requireNonNull(from, "from must not be null");
        java.util.Objects.requireNonNull(to, "to must not be null");
        if (from == to) {
            return false;
        }
        Set<CapabilityLifecycle> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Validates a lifecycle transition, throwing an exception if the
     * transition is not permitted by the state machine rules.
     *
     * <p>The exception message describes the invalid transition and lists
     * the allowed target states (if any).</p>
     *
     * @param from the current lifecycle state
     * @param to the target lifecycle state
     * @throws IllegalArgumentException if the transition is not permitted
     * @throws NullPointerException if {@code from} or {@code to} is null
     */
    public void validateTransition(CapabilityLifecycle from, CapabilityLifecycle to) {
        if (!canTransition(from, to)) {
            if (TERMINAL_STATES.contains(from)) {
                throw new IllegalArgumentException(
                        "Illegal lifecycle transition: " + from + " is a terminal state; "
                                + "no further transitions are permitted"
                );
            }
            Set<CapabilityLifecycle> allowed = TRANSITIONS.get(from);
            if (allowed == null || allowed.isEmpty()) {
                throw new IllegalArgumentException(
                        "Illegal lifecycle transition from " + from + " to " + to
                                + "; no outgoing transitions are defined for " + from
                );
            }
            throw new IllegalArgumentException(
                    "Illegal lifecycle transition from " + from + " to " + to
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
    public Set<CapabilityLifecycle> allowedTransitions(CapabilityLifecycle from) {
        java.util.Objects.requireNonNull(from, "from must not be null");
        Set<CapabilityLifecycle> allowed = TRANSITIONS.get(from);
        return allowed != null ? Set.copyOf(allowed) : Set.of();
    }

    /**
     * Returns whether the given state is terminal (no outgoing transitions).
     *
     * @param state the state to check
     * @return {@code true} if the state is terminal; {@code false} otherwise
     * @throws NullPointerException if {@code state} is null
     */
    public boolean isTerminal(CapabilityLifecycle state) {
        java.util.Objects.requireNonNull(state, "state must not be null");
        return TERMINAL_STATES.contains(state);
    }
}
