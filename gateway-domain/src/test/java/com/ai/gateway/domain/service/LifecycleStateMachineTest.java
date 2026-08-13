package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class LifecycleStateMachineTest {
    private final LifecycleStateMachine sm = new LifecycleStateMachine();

    @ParameterizedTest
    @CsvSource({
            "DRAFT, VALIDATED, true",
            "DRAFT, REJECTED, true",
            "VALIDATED, APPROVED, true",
            "VALIDATED, REJECTED, true",
            "APPROVED, PUBLISHED, true",
            "APPROVED, REJECTED, true",
            "PUBLISHED, SUSPENDED, true",
            "PUBLISHED, RETIRED, true",
            "SUSPENDED, PUBLISHED, true",
            "SUSPENDED, RETIRED, true",
            "DRAFT, PUBLISHED, false",
            "DRAFT, APPROVED, false",
            "RETIRED, PUBLISHED, false",
            "REJECTED, VALIDATED, false",
            "PUBLISHED, DRAFT, false"
    })
    void shouldValidateTransitions(CapabilityLifecycle from, CapabilityLifecycle to, boolean expected) {
        assertThat(sm.canTransition(from, to)).isEqualTo(expected);
    }

    @Test
    void shouldThrowOnInvalidTransition() {
        assertThatThrownBy(() -> sm.validateTransition(CapabilityLifecycle.DRAFT, CapabilityLifecycle.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowOnTransitionFromTerminalState() {
        assertThatThrownBy(() -> sm.validateTransition(CapabilityLifecycle.RETIRED, CapabilityLifecycle.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal state");
    }

    @Test
    void shouldNotAllowSelfTransition() {
        assertThat(sm.canTransition(CapabilityLifecycle.DRAFT, CapabilityLifecycle.DRAFT)).isFalse();
        assertThat(sm.canTransition(CapabilityLifecycle.PUBLISHED, CapabilityLifecycle.PUBLISHED)).isFalse();
    }

    @Test
    void shouldReturnAllowedTransitions() {
        assertThat(sm.allowedTransitions(CapabilityLifecycle.DRAFT))
                .containsExactlyInAnyOrder(CapabilityLifecycle.VALIDATED, CapabilityLifecycle.REJECTED);
    }

    @Test
    void shouldReturnEmptyForTerminalState() {
        assertThat(sm.allowedTransitions(CapabilityLifecycle.RETIRED)).isEmpty();
        assertThat(sm.allowedTransitions(CapabilityLifecycle.REJECTED)).isEmpty();
    }

    @Test
    void shouldIdentifyTerminalStates() {
        assertThat(sm.isTerminal(CapabilityLifecycle.RETIRED)).isTrue();
        assertThat(sm.isTerminal(CapabilityLifecycle.REJECTED)).isTrue();
        assertThat(sm.isTerminal(CapabilityLifecycle.DRAFT)).isFalse();
        assertThat(sm.isTerminal(CapabilityLifecycle.PUBLISHED)).isFalse();
    }
}
