package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.OperationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class OperationStateMachineTest {
    private final OperationStateMachine sm = new OperationStateMachine();

    @ParameterizedTest
    @CsvSource({
            "PREPARED, EXECUTING, true",
            "PREPARED, EXPIRED, true",
            "PREPARED, CANCELLED, true",
            "EXECUTING, SUCCEEDED, true",
            "EXECUTING, FAILED, true",
            "EXECUTING, UNKNOWN, true",
            "UNKNOWN, SUCCEEDED, true",
            "UNKNOWN, FAILED, true",
            "UNKNOWN, MANUAL_REVIEW, true",
            "PREPARED, SUCCEEDED, false",
            "SUCCEEDED, FAILED, false",
            "FAILED, EXECUTING, false",
            "EXPIRED, EXECUTING, false",
            "CANCELLED, EXECUTING, false",
            "MANUAL_REVIEW, SUCCEEDED, false"
    })
    void shouldValidateTransitions(OperationState from, OperationState to, boolean expected) {
        assertThat(sm.canTransition(from, to)).isEqualTo(expected);
    }

    @Test
    void shouldNotAllowSelfTransition() {
        assertThat(sm.canTransition(OperationState.PREPARED, OperationState.PREPARED)).isFalse();
        assertThat(sm.canTransition(OperationState.EXECUTING, OperationState.EXECUTING)).isFalse();
    }

    @Test
    void shouldThrowOnInvalidTransition() {
        assertThatThrownBy(() -> sm.validateTransition(OperationState.PREPARED, OperationState.SUCCEEDED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowOnTransitionFromTerminalState() {
        assertThatThrownBy(() -> sm.validateTransition(OperationState.SUCCEEDED, OperationState.FAILED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal state");
    }

    @Test
    void shouldIdentifyTerminalStates() {
        assertThat(sm.isTerminal(OperationState.SUCCEEDED)).isTrue();
        assertThat(sm.isTerminal(OperationState.FAILED)).isTrue();
        assertThat(sm.isTerminal(OperationState.EXPIRED)).isTrue();
        assertThat(sm.isTerminal(OperationState.CANCELLED)).isTrue();
        assertThat(sm.isTerminal(OperationState.MANUAL_REVIEW)).isTrue();
        assertThat(sm.isTerminal(OperationState.PREPARED)).isFalse();
        assertThat(sm.isTerminal(OperationState.EXECUTING)).isFalse();
        assertThat(sm.isTerminal(OperationState.UNKNOWN)).isFalse();
    }

    @Test
    void shouldProhibitAutoRetryForUnknownState() {
        assertThat(sm.prohibitsAutoRetry(OperationState.UNKNOWN)).isTrue();
        assertThat(sm.prohibitsAutoRetry(OperationState.EXECUTING)).isFalse();
        assertThat(sm.prohibitsAutoRetry(OperationState.PREPARED)).isFalse();
    }

    @Test
    void shouldReturnAllowedTransitions() {
        assertThat(sm.allowedTransitions(OperationState.PREPARED))
                .containsExactlyInAnyOrder(OperationState.EXECUTING, OperationState.EXPIRED, OperationState.CANCELLED);
        assertThat(sm.allowedTransitions(OperationState.EXECUTING))
                .containsExactlyInAnyOrder(OperationState.SUCCEEDED, OperationState.FAILED, OperationState.UNKNOWN);
    }

    @Test
    void shouldReturnEmptyForTerminalState() {
        assertThat(sm.allowedTransitions(OperationState.SUCCEEDED)).isEmpty();
        assertThat(sm.allowedTransitions(OperationState.FAILED)).isEmpty();
    }
}
