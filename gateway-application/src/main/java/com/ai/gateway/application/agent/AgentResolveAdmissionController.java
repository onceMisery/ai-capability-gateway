package com.ai.gateway.application.agent;

import com.ai.gateway.domain.port.TelemetryPort;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Non-queueing admission control for the bounded Resolve hot path. */
public final class AgentResolveAdmissionController {

    private final int maxConcurrent;
    private final TelemetryPort telemetry;
    private final Semaphore permits;
    private final AtomicInteger inFlight = new AtomicInteger();

    public AgentResolveAdmissionController(int maxConcurrent, TelemetryPort telemetry) {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        this.maxConcurrent = maxConcurrent;
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
        this.permits = new Semaphore(maxConcurrent);
        telemetry.recordValue("gateway.agent.resolve.capacity", maxConcurrent,
                Map.of("resource", "concurrent"));
        recordInFlight(0L);
    }

    public Permit tryAcquire() {
        if (!permits.tryAcquire()) {
            telemetry.increment("gateway.agent.resolve.admission",
                    Map.of("outcome", "capacity_rejected"));
            return null;
        }
        int current = inFlight.incrementAndGet();
        recordInFlight(current);
        telemetry.increment("gateway.agent.resolve.admission",
                Map.of("outcome", "admitted"));
        return new Permit(this);
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int inFlight() {
        return inFlight.get();
    }

    private void release() {
        int current = inFlight.decrementAndGet();
        permits.release();
        recordInFlight(current);
    }

    private void recordInFlight(long value) {
        telemetry.recordValue("gateway.agent.resolve.in_flight", value,
                Map.of("resource", "concurrent"));
    }

    public static final class Permit implements AutoCloseable {
        private final AgentResolveAdmissionController owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(AgentResolveAdmissionController owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }
}
