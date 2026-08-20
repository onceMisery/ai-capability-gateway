package com.ai.gateway.application.agent;

import com.ai.gateway.domain.port.TelemetryPort;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded reference implementation for a trusted Host process. */
public final class InMemoryPendingConfirmationStore implements PendingConfirmationStore {

    private final int maxEntries;
    private final TelemetryPort telemetry;
    private final Map<String, PendingConfirmationState> states = new ConcurrentHashMap<>();
    private final AtomicLong expiredEvictions = new AtomicLong();
    private final AtomicLong capacityRejections = new AtomicLong();

    public InMemoryPendingConfirmationStore(int maxEntries) {
        this(maxEntries, null);
    }

    public InMemoryPendingConfirmationStore(int maxEntries, TelemetryPort telemetry) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.telemetry = telemetry;
        recordCapacity();
    }

    @Override
    public synchronized void put(PendingConfirmationState state) {
        Objects.requireNonNull(state, "state must not be null");
        evictExpired();
        if (!states.containsKey(state.operationId()) && states.size() >= maxEntries) {
            capacityRejections.incrementAndGet();
            increment("capacity_rejected");
            throw new IllegalStateException("pending confirmation store capacity exceeded");
        }
        states.put(state.operationId(), state);
        increment("stored");
        recordSize();
    }

    @Override
    public Optional<PendingConfirmationState> find(
            String operationId, String principalDigest) {
        PendingConfirmationState state = states.get(operationId);
        if (state == null || !state.principalDigest().equals(principalDigest)) {
            return Optional.empty();
        }
        if (state.expiresAt().isBefore(Instant.now())) {
            if (states.remove(operationId, state)) {
                recordExpiredEviction();
                recordSize();
            }
            return Optional.empty();
        }
        return Optional.of(state);
    }

    @Override
    public Optional<PendingConfirmationState> beginConfirm(
            String operationId, String principalDigest) {
        final PendingConfirmationState[] claimed = new PendingConfirmationState[1];
        states.computeIfPresent(operationId, (key, current) -> {
            if (current.expiresAt().isBefore(Instant.now())) {
                recordExpiredEviction();
                return null;
            }
            if (!current.principalDigest().equals(principalDigest)
                    || current.status() != PendingConfirmationState.Status.PENDING) {
                return current;
            }
            PendingConfirmationState next = current.transition(
                    PendingConfirmationState.Status.CONFIRMING);
            claimed[0] = next;
            return next;
        });
        recordSize();
        return Optional.ofNullable(claimed[0]);
    }

    @Override
    public void replace(PendingConfirmationState state) {
        Objects.requireNonNull(state, "state must not be null");
        states.replace(state.operationId(), state);
    }

    @Override
    public void remove(String operationId) {
        states.remove(operationId);
        recordSize();
    }

    public int size() {
        return states.size();
    }

    public long expiredEvictionCount() {
        return expiredEvictions.get();
    }

    public long capacityRejectionCount() {
        return capacityRejections.get();
    }

    private void evictExpired() {
        Instant now = Instant.now();
        states.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().expiresAt().isBefore(now);
            if (expired) {
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
                    Map.of("resource", "pending_confirmation"));
            recordSize();
        }
    }

    private void recordSize() {
        if (telemetry != null) {
            telemetry.recordValue("gateway.agent.store.entries", states.size(),
                    Map.of("resource", "pending_confirmation"));
        }
    }

    private void increment(String outcome) {
        if (telemetry != null) {
            telemetry.increment("gateway.agent.pending_confirmation", Map.of("outcome", outcome));
        }
    }
}
