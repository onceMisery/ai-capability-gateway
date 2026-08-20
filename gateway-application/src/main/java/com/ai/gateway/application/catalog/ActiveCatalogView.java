package com.ai.gateway.application.catalog;

import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityReference;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.TelemetryPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Immutable, atomically published runtime catalog view. */
public final class ActiveCatalogView {

    private final CatalogSnapshot snapshot;
    private final Instant activatedAt;
    private final List<CapabilityManifest> capabilities;
    private final Map<CapabilityReference, CapabilityManifest> byReference;
    private final Map<CapabilityReference, Integer> ordinalByReference;
    private final Map<CapabilityReference, CapabilityPublicProjectionService.Projection>
            publicProjections;
    private final LuceneCandidateRetriever.IndexHandle indexHandle;
    private final TelemetryPort telemetry;
    private final long leaseHoldTimeoutMs;
    private final Object leaseMonitor = new Object();
    private int leaseCount;
    private boolean retired;

    private ActiveCatalogView(CatalogSnapshot snapshot, Instant activatedAt,
                              CapabilityPublicProjectionService projectionService,
                              LuceneCandidateRetriever.IndexHandle indexHandle,
                              TelemetryPort telemetry,
                              long leaseHoldTimeoutMs) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        this.capabilities = List.copyOf(snapshot.capabilities());

        Map<CapabilityReference, CapabilityManifest> references = new LinkedHashMap<>();
        Map<CapabilityReference, Integer> ordinals = new LinkedHashMap<>();
        Map<CapabilityReference, CapabilityPublicProjectionService.Projection> projections =
                new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < capabilities.size(); ordinal++) {
            CapabilityManifest manifest = Objects.requireNonNull(
                    capabilities.get(ordinal), "capability must not be null");
            CapabilityReference reference = CapabilityReference.from(manifest);
            if (references.put(reference, manifest) != null) {
                throw new IllegalArgumentException("duplicate capability binding: " + reference);
            }
            ordinals.put(reference, ordinal);
            CapabilityPublicProjectionService.Projection projection = projectionService
                    .project(manifest)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "capability rejected by Agent projection governance: " + reference));
            projections.put(reference, projection);
        }
        this.byReference = Map.copyOf(references);
        this.ordinalByReference = Map.copyOf(ordinals);
        this.publicProjections = Map.copyOf(projections);
        this.indexHandle = indexHandle;
        this.telemetry = telemetry;
        this.leaseHoldTimeoutMs = leaseHoldTimeoutMs;
    }

    public static ActiveCatalogView from(CatalogSnapshot snapshot) {
        return from(snapshot, new CapabilityPublicProjectionService());
    }

    public static ActiveCatalogView from(
            CatalogSnapshot snapshot,
            CapabilityPublicProjectionService projectionService) {
        return new ActiveCatalogView(snapshot, Instant.now(),
                Objects.requireNonNull(projectionService, "projectionService must not be null"),
                null, null, 0L);
    }

    public static ActiveCatalogView from(
            CatalogSnapshot snapshot,
            CapabilityPublicProjectionService projectionService,
            LuceneCandidateRetriever.IndexHandle indexHandle) {
        return new ActiveCatalogView(snapshot, Instant.now(),
                Objects.requireNonNull(projectionService, "projectionService must not be null"),
                indexHandle, null, 0L);
    }

    public static ActiveCatalogView from(
            CatalogSnapshot snapshot,
            CapabilityPublicProjectionService projectionService,
            LuceneCandidateRetriever.IndexHandle indexHandle,
            TelemetryPort telemetry) {
        return from(snapshot, projectionService, indexHandle, telemetry, 0L);
    }

    public static ActiveCatalogView from(
            CatalogSnapshot snapshot,
            CapabilityPublicProjectionService projectionService,
            LuceneCandidateRetriever.IndexHandle indexHandle,
            TelemetryPort telemetry,
            long leaseHoldTimeoutMs) {
        return new ActiveCatalogView(snapshot, Instant.now(),
                Objects.requireNonNull(projectionService, "projectionService must not be null"),
                indexHandle, telemetry, leaseHoldTimeoutMs);
    }

    public CatalogSnapshot snapshot() {
        return snapshot;
    }

    public long catalogVersion() {
        return snapshot.snapshotVersion();
    }

    public Instant activatedAt() {
        return activatedAt;
    }

    public LuceneCandidateRetriever.IndexHandle indexHandle() {
        return indexHandle;
    }

    /** Acquires a request lease so the view's index remains open while in use. */
    public ViewLease acquireLease() {
        synchronized (leaseMonitor) {
            if (retired) {
                return null;
            }
            leaseCount++;
            recordLeaseCount("active", leaseCount);
            return new ViewLease(this, System.nanoTime());
        }
    }

    void retire() {
        synchronized (leaseMonitor) {
            retired = true;
            recordLeaseCount("active", 0L);
            recordLeaseCount("retired", leaseCount);
            closeIndexIfUnused();
        }
    }

    private void releaseLease() {
        synchronized (leaseMonitor) {
            leaseCount--;
            recordLeaseCount(retired ? "retired" : "active", leaseCount);
            closeIndexIfUnused();
        }
    }

    private void closeIndexIfUnused() {
        if (retired && leaseCount == 0 && indexHandle != null) {
            try {
                indexHandle.close();
            } catch (Exception ignored) {
                // Closing an in-memory index is best effort during rotation.
            }
        }
    }

    int activeLeaseCount() {
        synchronized (leaseMonitor) {
            return leaseCount;
        }
    }

    boolean isRetiredAndDrained() {
        synchronized (leaseMonitor) {
            return retired && leaseCount == 0;
        }
    }

    private void recordLeaseCount(String resource, long value) {
        if (telemetry != null) {
            telemetry.recordValue("gateway.catalog.view.leases", value,
                    Map.of("resource", resource));
        }
    }

    /** A closeable request pin for one immutable catalog view. */
    public static final class ViewLease implements AutoCloseable {
        private final ActiveCatalogView view;
        private final long createdAtNanos;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ViewLease(ActiveCatalogView view, long createdAtNanos) {
            this.view = view;
            this.createdAtNanos = createdAtNanos;
        }

        public ActiveCatalogView view() {
            return view;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                view.recordLeaseHold(createdAtNanos);
                view.releaseLease();
            }
        }
    }

    private void recordLeaseHold(long createdAtNanos) {
        long ageMs = (System.nanoTime() - createdAtNanos) / 1_000_000L;
        if (telemetry == null || ageMs < 0L) {
            return;
        }
        telemetry.recordDuration("gateway.catalog.view.lease.hold.duration",
                ageMs * 1_000_000L,
                Map.of("resource", retired ? "retired" : "active"));
        if (leaseHoldTimeoutMs > 0L && ageMs > leaseHoldTimeoutMs) {
            telemetry.increment("gateway.catalog.view.lease.timeout",
                    Map.of("resource", retired ? "retired" : "active"));
        }
    }

    public List<CapabilityManifest> capabilities() {
        return capabilities;
    }

    public Optional<CapabilityManifest> find(CapabilityReference reference) {
        return Optional.ofNullable(byReference.get(reference));
    }

    public Optional<CapabilityManifest> find(String capabilityId, String version) {
        return find(new CapabilityReference(capabilityId, version));
    }

    public Optional<CapabilityPublicProjectionService.Projection> publicProjection(
            CapabilityManifest manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        return Optional.ofNullable(publicProjections.get(CapabilityReference.from(manifest)));
    }

    /** Resolves a visibility decision through the immutable ordinal index. */
    public List<CapabilityManifest> visibleCapabilities(CapabilityVisibility visibility) {
        Objects.requireNonNull(visibility, "visibility must not be null");
        if (!visibility.healthy()) {
            return List.of();
        }
        if (visibility.allVisible()) {
            return capabilities;
        }

        BitSet visibleOrdinals = new BitSet(capabilities.size());
        for (CapabilityReference reference : visibility.visibleCapabilities()) {
            if ("*".equals(reference.version())) {
                for (Map.Entry<CapabilityReference, Integer> entry : ordinalByReference.entrySet()) {
                    if (entry.getKey().capabilityId().equals(reference.capabilityId())) {
                        visibleOrdinals.set(entry.getValue());
                    }
                }
                continue;
            }
            Integer ordinal = ordinalByReference.get(reference);
            if (ordinal != null) {
                visibleOrdinals.set(ordinal);
            }
        }

        List<CapabilityManifest> result = new ArrayList<>(visibleOrdinals.cardinality());
        for (int ordinal = visibleOrdinals.nextSetBit(0);
             ordinal >= 0;
             ordinal = visibleOrdinals.nextSetBit(ordinal + 1)) {
            result.add(capabilities.get(ordinal));
        }
        return List.copyOf(result);
    }
}
