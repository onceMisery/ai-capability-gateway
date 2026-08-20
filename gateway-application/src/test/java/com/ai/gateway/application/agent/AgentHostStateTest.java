package com.ai.gateway.application.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentHostStateTest {

    @Test
    void modelMapperStoresConfirmationTokenPrivatelyAndOmitsItFromModelResult() {
        InMemoryPendingConfirmationStore store = new InMemoryPendingConfirmationStore(4);
        AgentModelResultMapper mapper = new AgentModelResultMapper(store);
        Instant expiresAt = Instant.now().plusSeconds(60);
        AgentHostToolCallUseCase.Result gatewayResult = new AgentHostToolCallUseCase.Result(
                AgentHostToolCallUseCase.Status.CONFIRMATION_REQUIRED, null, null,
                "Cancel order SO-1", 8L, 42L, "op-1", "secret-confirmation-token",
                true, expiresAt);

        AgentModelResultMapper.ModelResult modelResult = mapper.map(
                gatewayResult, "principal-digest", "arguments-digest");

        assertThat(modelResult.status())
                .isEqualTo(AgentModelResultMapper.ModelResult.Status.CONFIRMATION_REQUIRED);
        assertThat(modelResult.operationId()).isEqualTo("op-1");
        assertThat(modelResult.toString()).doesNotContain("secret-confirmation-token");
        assertThat(store.find("op-1", "principal-digest"))
                .get()
                .satisfies(state -> {
                    assertThat(state.confirmationToken()).isEqualTo("secret-confirmation-token");
                    assertThat(state.status()).isEqualTo(PendingConfirmationState.Status.PENDING);
                });
    }

    @Test
    void confirmationClaimIsAtomicAndCannotBeClaimedTwice() {
        InMemoryPendingConfirmationStore store = new InMemoryPendingConfirmationStore(4);
        store.put(new PendingConfirmationState(
                "op-1", "token", "principal", "args", "summary",
                Instant.now().plusSeconds(60), PendingConfirmationState.Status.PENDING));

        assertThat(store.beginConfirm("op-1", "principal")).isPresent();
        assertThat(store.beginConfirm("op-1", "principal")).isEmpty();
        assertThat(store.find("op-1", "principal")).get()
                .extracting(PendingConfirmationState::status)
                .isEqualTo(PendingConfirmationState.Status.CONFIRMING);
    }

    @Test
    void terminalConfirmationStateDestroysThePrivateToken() {
        PendingConfirmationState confirming = new PendingConfirmationState(
                "op-1", "token", "principal", "args", "summary",
                Instant.now().plusSeconds(60),
                PendingConfirmationState.Status.CONFIRMING);

        PendingConfirmationState terminal = confirming.transition(
                PendingConfirmationState.Status.UNKNOWN);

        assertThat(terminal.confirmationToken()).isNull();
        assertThatThrownBy(() -> new PendingConfirmationState(
                "op-1", "token", "principal", "args", "summary",
                Instant.now().plusSeconds(60),
                PendingConfirmationState.Status.CONFIRMED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingConfirmationStoreExposesBoundedCapacityEvidence() {
        InMemoryPendingConfirmationStore store = new InMemoryPendingConfirmationStore(1);
        store.put(new PendingConfirmationState(
                "op-1", "token", "principal", "args", "summary",
                Instant.now().plusSeconds(60), PendingConfirmationState.Status.PENDING));

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.capacityRejectionCount()).isZero();
        assertThat(store.expiredEvictionCount()).isZero();
    }

    @Test
    void turnStoreEnforcesCapacityAndExposesCapacityEvidence() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore(1);
        AgentCapabilityResolver.Resolution first = resolution("turn-1", "tr-1");
        store.put("principal", AgentTurnState.from("turn-1", "resolve-1", first));

        assertThatThrownBy(() -> store.put("principal",
                AgentTurnState.from("turn-2", "resolve-2",
                        resolution("turn-2", "tr-2"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.capacityRejectionCount()).isEqualTo(1);
        assertThat(store.expiredEvictionCount()).isZero();
    }

    @Test
    void identicalTurnIdsRemainIsolatedAcrossPrincipalsAndToolClaimIsAtomic() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore(2);
        store.put("principal-a", AgentTurnState.from(
                "turn-1", "resolve-a", resolution("turn-1", "ref-a")));
        store.put("principal-b", AgentTurnState.from(
                "turn-1", "resolve-b", resolution("turn-1", "ref-b")));

        assertThat(store.find("principal-a", "turn-1").orElseThrow()
                .state().allows("ref-a")).isTrue();
        assertThat(store.find("principal-b", "turn-1").orElseThrow()
                .state().allows("ref-b")).isTrue();
        assertThat(store.claimTool("principal-a", "turn-1", "ref-a")).isPresent();
        assertThat(store.claimTool("principal-a", "turn-1", "ref-b")).isEmpty();
    }

    @Test
    void turnRestrictsReferencesAndAllowsOnlyOneArgumentRepair() {
        AgentCapabilityResolver.Candidate candidate = new AgentCapabilityResolver.Candidate(
                "tr-1", "Query", "Query order",
                CapabilityPublicProjectionService.SchemaClass.SIMPLE,
                Map.of("required", List.of("orderNo")), "DIRECT");
        AgentCapabilityResolver.Resolution resolution = new AgentCapabilityResolver.Resolution(
                AgentCapabilityResolver.Status.RESOLVED, null, 8L, 42L,
                List.of(candidate), null, Instant.now().plusSeconds(60));

        AgentTurnState state = AgentTurnState.from("turn-1", "resolve-1", resolution);
        assertThat(state.allows("tr-1")).isTrue();
        assertThat(state.allows("tr-other")).isFalse();
        assertThat(state.recordArgumentRepair().argumentRepairCount()).isEqualTo(1);
        assertThatThrownBy(() -> state.recordArgumentRepair().recordArgumentRepair())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.select("tr-other",
                CapabilityPublicProjectionService.SchemaClass.SIMPLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AgentCapabilityResolver.Resolution resolution(
            String turnId, String toolRef) {
        AgentCapabilityResolver.Candidate candidate = new AgentCapabilityResolver.Candidate(
                toolRef, "Query", "Query order",
                CapabilityPublicProjectionService.SchemaClass.SIMPLE,
                Map.of("required", List.of("orderNo")), "DIRECT");
        return new AgentCapabilityResolver.Resolution(
                AgentCapabilityResolver.Status.RESOLVED, null, 8L, 42L,
                List.of(candidate), null, Instant.now().plusSeconds(60));
    }
}
