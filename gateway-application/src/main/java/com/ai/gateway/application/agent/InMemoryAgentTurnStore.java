package com.ai.gateway.application.agent;

import com.ai.gateway.domain.port.TelemetryPort;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded in-memory reference store for trusted Host turn state. */
public final class InMemoryAgentTurnStore implements AgentTurnStore {

    private final int maxEntries;
    private final TelemetryPort telemetry;
    private final Map<TurnKey, StoredTurn> turns = new ConcurrentHashMap<>();
    private final Map<TurnKey, ResolvedTurn> resolvedTurns = new ConcurrentHashMap<>();
    private final AtomicLong expiredEvictions = new AtomicLong();
    private final AtomicLong capacityRejections = new AtomicLong();

    public InMemoryAgentTurnStore(int maxEntries) {
        this(maxEntries, null);
    }

    public InMemoryAgentTurnStore(int maxEntries, TelemetryPort telemetry) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.telemetry = telemetry;
        recordCapacity();
    }

    @Override
    public synchronized void put(String principalDigest, AgentTurnState state) {
        Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        Objects.requireNonNull(state, "state must not be null");
        evictExpired();
        TurnKey key = new TurnKey(principalDigest, state.agentTurnId());
        if (!turns.containsKey(key) && turns.size() >= maxEntries) {
            capacityRejections.incrementAndGet();
            increment("capacity_rejected");
            throw new IllegalStateException("agent turn store capacity exceeded");
        }
        turns.put(key, new StoredTurn(principalDigest, state));
        increment("stored");
        recordSize();
    }

    @Override
    public synchronized void putResolved(
            String principalDigest, AgentTurnState state, String resolveFingerprint,
            AgentCapabilityResolver.Resolution resolution) {
        put(principalDigest, state);
        TurnKey key = new TurnKey(principalDigest, state.agentTurnId());
        resolvedTurns.put(key, new ResolvedTurn(
                principalDigest, state, resolveFingerprint, resolution));
    }

    @Override
    public Optional<ResolvedTurn> findResolved(
            String principalDigest, String agentTurnId, String resolveFingerprint) {
        Optional<StoredTurn> current = find(principalDigest, agentTurnId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        ResolvedTurn resolved = resolvedTurns.get(new TurnKey(principalDigest, agentTurnId));
        if (resolved == null || !Objects.equals(
                resolved.resolveFingerprint(), resolveFingerprint)) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    @Override
    public Optional<StoredTurn> find(String principalDigest, String agentTurnId) {
        if (principalDigest == null || agentTurnId == null) {
            return Optional.empty();
        }
        TurnKey key = new TurnKey(principalDigest, agentTurnId);
        StoredTurn stored = turns.get(key);
        if (stored == null) {
            return Optional.empty();
        }
        if (stored.state().expiresAt().isBefore(Instant.now())) {
            if (turns.remove(key, stored)) {
                resolvedTurns.remove(key);
                recordExpiredEviction();
                recordSize();
            }
            return Optional.empty();
        }
        return Optional.of(stored);
    }

    @Override
    public Optional<StoredTurn> claimTool(
            String principalDigest, String agentTurnId, String toolRef) {
        if (principalDigest == null || agentTurnId == null || toolRef == null) {
            return Optional.empty();
        }
        TurnKey key = new TurnKey(principalDigest, agentTurnId);
        final StoredTurn[] claimed = new StoredTurn[1];
        turns.computeIfPresent(key, (ignored, current) -> {
            AgentTurnState state = current.state();
            if (state.expiresAt().isBefore(Instant.now()) || !state.allows(toolRef)
                    || (state.selectedToolRef() != null
                    && !state.selectedToolRef().equals(toolRef))) {
                if (state.expiresAt().isBefore(Instant.now())) {
                    resolvedTurns.remove(key);
                    recordExpiredEviction();
                    return null;
                }
                return current;
            }
            AgentTurnState selected = state.selectedToolRef() == null
                    ? state.select(toolRef, state.schemaClass()) : state;
            claimed[0] = new StoredTurn(principalDigest, selected);
            return claimed[0];
        });
        recordSize();
        return Optional.ofNullable(claimed[0]);
    }

    @Override
    public void replace(String principalDigest, AgentTurnState state) {
        Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        Objects.requireNonNull(state, "state must not be null");
        turns.computeIfPresent(new TurnKey(principalDigest, state.agentTurnId()),
                (key, current) -> new StoredTurn(principalDigest, state));
        resolvedTurns.computeIfPresent(new TurnKey(principalDigest, state.agentTurnId()),
                (key, current) -> new ResolvedTurn(principalDigest, state,
                        current.resolveFingerprint(), current.resolution()));
    }

    public int size() {
        return turns.size();
    }

    public long expiredEvictionCount() {
        return expiredEvictions.get();
    }

    public long capacityRejectionCount() {
        return capacityRejections.get();
    }

    private void evictExpired() {
        Instant now = Instant.now();
        turns.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().state().expiresAt().isBefore(now);
            if (expired) {
                resolvedTurns.remove(entry.getKey());
                recordExpiredEviction();
            }
            return expired;
        });
        recordSize();
    }

    private void recordExpiredEviction() {
        expiredEvictions.incrementAndGet();
        increment("expired_evicted");
    }

    private void recordCapacity() {
        if (telemetry != null) {
            telemetry.recordValue("gateway.agent.store.capacity", maxEntries,
                    Map.of("resource", "turn"));
            recordSize();
        }
    }

    private void recordSize() {
        if (telemetry != null) {
            telemetry.recordValue("gateway.agent.store.entries", turns.size(),
                    Map.of("resource", "turn"));
        }
    }

    private void increment(String outcome) {
        if (telemetry != null) {
            telemetry.increment("gateway.agent.turn_store", Map.of("outcome", outcome));
        }
    }

    private record TurnKey(String principalDigest, String agentTurnId) {
    }
}
