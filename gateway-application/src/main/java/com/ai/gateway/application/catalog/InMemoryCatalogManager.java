package com.ai.gateway.application.catalog;

import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;

/**
 * In-memory catalog manager that maintains the current active snapshot using
 * atomic reference swapping.
 *
 * <p>When a snapshot notification is received, the manager:</p>
 * <ol>
 * <li>Loads the new snapshot from PostgreSQL via {@link CatalogPort}.</li>
 * <li>Builds a retrieval index for fast capability lookup.</li>
 * <li>Verifies the snapshot digest for integrity.</li>
 * <li>Atomically replaces the in-memory reference.</li>
 * </ol>
 *
 * <p>On loading failure, the old snapshot is retained. The
 * instance exits the ready state after exceeding the maximum lag time.</p>
 *
 * <p>Each request is pinned to the snapshot version active at the start of
 * processing. The {@link #isStale(long)} method allows health
 * checks to determine if the instance's snapshot is behind the threshold.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: the snapshot reference is stored in an
 * {@link AtomicReference} and all reads are lock-free.</p>
 *
 * @see CatalogPort
 * @see CatalogSnapshot
 * @since 0.1.0
 */
public final class InMemoryCatalogManager {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCatalogManager.class);

    private final CatalogPort catalogPort;
    private final CapabilityPublicProjectionService projectionService;
    private final LuceneCandidateRetriever candidateRetriever;
    private final TelemetryPort telemetry;
    private final int maxCapabilities;
    private final long maxIndexBytes;
    private final long maxProcessMemoryBytes;
    private final long buildTimeoutMs;
    private final long leaseHoldTimeoutMs;
    private final Executor refreshExecutor;
    private final AtomicReference<ActiveCatalogView> activeView = new AtomicReference<>();
    private final AtomicReference<ActiveCatalogView> retiredView = new AtomicReference<>();
    private final AtomicReference<Long> lastLoadTime = new AtomicReference<>(0L);
    private final AtomicReference<String> pendingRefreshEnvironment = new AtomicReference<>();
    private final AtomicBoolean refreshWorkerScheduled = new AtomicBoolean();

    /**
     * Constructs a new InMemoryCatalogManager with the required dependency.
     *
     * @param catalogPort the port for loading snapshots from PostgreSQL
     * @throws NullPointerException if {@code catalogPort} is null
     */
    public InMemoryCatalogManager(CatalogPort catalogPort) {
        this(catalogPort, new CapabilityPublicProjectionService(), null,
                null, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public InMemoryCatalogManager(
            CatalogPort catalogPort,
            CapabilityPublicProjectionService projectionService) {
        this(catalogPort, projectionService, null,
                null, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public InMemoryCatalogManager(
            CatalogPort catalogPort,
            CapabilityPublicProjectionService projectionService,
            LuceneCandidateRetriever candidateRetriever) {
        this(catalogPort, projectionService, candidateRetriever,
                null, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public InMemoryCatalogManager(
            CatalogPort catalogPort,
            CapabilityPublicProjectionService projectionService,
            LuceneCandidateRetriever candidateRetriever,
            TelemetryPort telemetry,
            int maxCapabilities,
            long maxIndexBytes) {
        this(catalogPort, projectionService, candidateRetriever, telemetry,
                maxCapabilities, maxIndexBytes, Runnable::run);
    }

    /**
     * Constructs a manager with an isolated executor for notification-driven
     * catalog builds. The executor must be bounded by the composition root.
     */
    public InMemoryCatalogManager(
            CatalogPort catalogPort,
            CapabilityPublicProjectionService projectionService,
            LuceneCandidateRetriever candidateRetriever,
            TelemetryPort telemetry,
            int maxCapabilities,
            long maxIndexBytes,
            Executor refreshExecutor) {
        this(catalogPort, projectionService, candidateRetriever, telemetry,
                maxCapabilities, maxIndexBytes, Long.MAX_VALUE, Long.MAX_VALUE, 0L,
                refreshExecutor);
    }

    public InMemoryCatalogManager(
            CatalogPort catalogPort,
            CapabilityPublicProjectionService projectionService,
            LuceneCandidateRetriever candidateRetriever,
            TelemetryPort telemetry,
            int maxCapabilities,
            long maxIndexBytes,
            long maxProcessMemoryBytes,
            long buildTimeoutMs,
            long leaseHoldTimeoutMs,
            Executor refreshExecutor) {
        this.catalogPort = Objects.requireNonNull(catalogPort,
                "catalogPort must not be null");
        this.projectionService = Objects.requireNonNull(
                projectionService, "projectionService must not be null");
        this.candidateRetriever = candidateRetriever;
        this.telemetry = telemetry;
        if (maxCapabilities <= 0) {
            throw new IllegalArgumentException("maxCapabilities must be positive");
        }
        if (maxIndexBytes <= 0) {
            throw new IllegalArgumentException("maxIndexBytes must be positive");
        }
        if (maxProcessMemoryBytes <= 0) {
            throw new IllegalArgumentException("maxProcessMemoryBytes must be positive");
        }
        if (buildTimeoutMs <= 0) {
            throw new IllegalArgumentException("buildTimeoutMs must be positive");
        }
        if (leaseHoldTimeoutMs < 0) {
            throw new IllegalArgumentException("leaseHoldTimeoutMs must not be negative");
        }
        this.maxCapabilities = maxCapabilities;
        this.maxIndexBytes = maxIndexBytes;
        this.maxProcessMemoryBytes = maxProcessMemoryBytes;
        this.buildTimeoutMs = buildTimeoutMs;
        this.leaseHoldTimeoutMs = leaseHoldTimeoutMs;
        this.refreshExecutor = Objects.requireNonNull(refreshExecutor,
                "refreshExecutor must not be null");
        recordValue("gateway.catalog.view.capacity", maxCapabilities, "capabilities");
        recordValue("gateway.catalog.index.capacity", maxIndexBytes, "bytes");
        recordValue("gateway.catalog.process-memory.capacity", maxProcessMemoryBytes, "bytes");
        recordValue("gateway.catalog.build-timeout.capacity", buildTimeoutMs, "milliseconds");
    }

    /**
     * Schedules a notification-driven refresh without running catalog I/O or
     * index construction on the publisher/listener thread.
     *
     * <p>Only one worker is scheduled at a time. While it is running, a newer
     * notification replaces the pending environment value, so a burst of
     * publish messages cannot create an unbounded refresh queue. The worker
     * still loads the latest snapshot from the CatalogPort, therefore the
     * notification version is intentionally treated only as a wake-up hint.</p>
     *
     * @return {@code true} when a worker was scheduled, {@code false} when the
     * notification was coalesced or the executor rejected it
     */
    public boolean requestRefresh(String environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        pendingRefreshEnvironment.set(environment);
        if (!refreshWorkerScheduled.compareAndSet(false, true)) {
            incrementRefresh("notification_coalesced");
            return false;
        }
        try {
            refreshExecutor.execute(this::drainRefreshNotifications);
            incrementRefresh("notification_scheduled");
            return true;
        } catch (RejectedExecutionException e) {
            refreshWorkerScheduled.set(false);
            pendingRefreshEnvironment.compareAndSet(environment, null);
            incrementRefresh("notification_rejected");
            log.warn("Catalog refresh executor rejected notification for environment={}",
                    environment);
            return false;
        }
    }

    private void drainRefreshNotifications() {
        try {
            while (true) {
                String environment = pendingRefreshEnvironment.getAndSet(null);
                if (environment == null) {
                    return;
                }
                try {
                    boolean loaded = loadAndActivate(environment);
                    log.info("Catalog refresh completed asynchronously: activated={}, version={}",
                            loaded, getCurrentSnapshotVersion());
                } catch (RuntimeException e) {
                    log.error("Catalog refresh worker failed, retaining active view: {}",
                            e.getMessage());
                    incrementRefresh("worker_failed");
                }
            }
        } finally {
            refreshWorkerScheduled.set(false);
            // Close the race where a notification arrived after the last
            // getAndSet but before the worker released its scheduled marker.
            if (pendingRefreshEnvironment.get() != null
                    && refreshWorkerScheduled.compareAndSet(false, true)) {
                try {
                    refreshExecutor.execute(this::drainRefreshNotifications);
                    incrementRefresh("notification_rescheduled");
                } catch (RejectedExecutionException e) {
                    refreshWorkerScheduled.set(false);
                    incrementRefresh("notification_rejected");
                    log.warn("Catalog refresh executor rejected rescheduled notification");
                }
            }
        }
    }

    /**
     * Loads the current snapshot from PostgreSQL, builds the retrieval index,
     * verifies the digest, and atomically replaces the in-memory reference
     *
     * <p>If loading or digest verification fails, the old snapshot is retained
     * and an error is logged. The instance should exit the ready state after
     * exceeding the maximum lag time.</p>
     *
     * @param environment the target environment (e.g., "production")
     * @return {@code true} if the snapshot was successfully loaded and activated
     */
    public synchronized boolean loadAndActivate(String environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        long started = System.nanoTime();
        String outcome = "load_failed";
        log.info("Loading catalog snapshot for environment: {}", environment);

        ActiveCatalogView draining = retiredView.get();
        if (draining != null) {
            if (draining.isRetiredAndDrained()) {
                retiredView.compareAndSet(draining, null);
            } else {
                outcome = "retired_view_busy";
                incrementRefresh(outcome);
                recordRefreshDuration(started, outcome);
                log.warn("Catalog activation rejected while retired view version {} still has {} leases",
                        draining.catalogVersion(), draining.activeLeaseCount());
                return false;
            }
        }

        CatalogSnapshot newSnapshot;
        try {
            newSnapshot = catalogPort.loadCurrentSnapshot(environment);
        } catch (Exception e) {
            incrementRefresh(outcome);
            recordRefreshDuration(started, outcome);
            log.error("Failed to load snapshot from PostgreSQL: {}", e.getMessage());
            return false;
        }

        if (newSnapshot == null) {
            outcome = "snapshot_missing";
            incrementRefresh(outcome);
            recordRefreshDuration(started, outcome);
            log.warn("No snapshot returned for environment: {}", environment);
            return false;
        }
        if (!environment.equals(newSnapshot.environment()) || newSnapshot.snapshotVersion() <= 0
                || newSnapshot.capabilities().isEmpty()) {
            outcome = "snapshot_invalid";
            incrementRefresh(outcome);
            recordRefreshDuration(started, outcome);
            log.warn("Snapshot is not ready for activation: expectedEnvironment={}, actualEnvironment={}, version={}, capabilities={}",
                    environment, newSnapshot.environment(), newSnapshot.snapshotVersion(),
                    newSnapshot.capabilities().size());
            return false;
        }
        if (newSnapshot.capabilities().size() > maxCapabilities) {
            outcome = "capability_capacity_rejected";
            incrementRefresh(outcome);
            recordRefreshDuration(started, outcome);
            log.warn("Catalog activation rejected: capabilities={} exceeds limit={}",
                    newSnapshot.capabilities().size(), maxCapabilities);
            return false;
        }

        // Verify the snapshot digest for integrity. Missing digests fail closed.
        String storedDigest = newSnapshot.digest();
        if (storedDigest == null || storedDigest.isBlank()) {
            outcome = "digest_missing";
            incrementRefresh(outcome);
            recordRefreshDuration(started, outcome);
            log.error("Snapshot version {} has no stored digest", newSnapshot.snapshotVersion());
            return false;
        }
        String computedDigest = computeSnapshotDigest(newSnapshot);
        if (!computedDigest.equals(storedDigest)) {
            outcome = "digest_invalid";
            incrementRefresh(outcome);
            recordRefreshDuration(started, outcome);
            log.error("Snapshot digest verification failed for version {}: expected={}, computed={}",
                    newSnapshot.snapshotVersion(), storedDigest, computedDigest);
            return false;
        }

        if (buildBudgetExceeded(started, newSnapshot, 0L)) {
            return false;
        }

        ActiveCatalogView newView;
        LuceneCandidateRetriever.IndexHandle newIndex = null;
        try {
            if (candidateRetriever != null) {
                newIndex = candidateRetriever.buildIndex(newSnapshot);
                if (newIndex.sizeBytes() > maxIndexBytes) {
                    outcome = "index_capacity_rejected";
                    newIndex.close();
                    incrementRefresh(outcome);
                    recordRefreshDuration(started, outcome);
                    log.warn("Catalog activation rejected: indexBytes={} exceeds limit={}",
                            newIndex.sizeBytes(), maxIndexBytes);
                    return false;
                }
                if (buildBudgetExceeded(started, newSnapshot, newIndex.sizeBytes())) {
                    newIndex.close();
                    return false;
                }
            }
            newView = ActiveCatalogView.from(
                    newSnapshot, projectionService, newIndex, telemetry, leaseHoldTimeoutMs);
            if (buildBudgetExceeded(started, newSnapshot,
                    newIndex == null ? 0L : newIndex.sizeBytes())) {
                if (newIndex != null) {
                    newIndex.close();
                }
                return false;
            }
        } catch (RuntimeException e) {
            if (newIndex != null) {
                try {
                    newIndex.close();
                } catch (Exception ignored) {
                    // The failed view is never published; cleanup is best effort.
                }
            }
            log.error("Failed to build active catalog view for version {}: {}",
                    newSnapshot.snapshotVersion(), e.getMessage());
            outcome = "view_build_failed";
            incrementRefresh(outcome);
            recordRefreshDuration(started, outcome);
            return false;
        } catch (Exception e) {
            log.error("Failed to enforce catalog capacity for version {}: {}",
                    newSnapshot.snapshotVersion(), e.getMessage());
            outcome = "index_capacity_check_failed";
            incrementRefresh(outcome);
            recordRefreshDuration(started, outcome);
            return false;
        }

        ActiveCatalogView currentView = activeView.get();
        if (currentView != null
                && newSnapshot.snapshotVersion() <= currentView.catalogVersion()) {
            newView.retire();
            outcome = "version_not_newer";
            incrementRefresh(outcome);
            recordRefreshDuration(started, outcome);
            log.info("Catalog activation ignored: incoming version {} is not newer than active version {}",
                    newSnapshot.snapshotVersion(), currentView.catalogVersion());
            return false;
        }

        // Snapshot and all lookup indexes become visible in one atomic write.
        if (candidateRetriever != null && newIndex != null) {
            candidateRetriever.publishIndex(newIndex);
        }
        ActiveCatalogView oldView = activeView.getAndSet(newView);
        if (oldView != null) {
            oldView.retire();
            if (!oldView.isRetiredAndDrained()) {
                retiredView.set(oldView);
            }
        }
        lastLoadTime.set(System.currentTimeMillis());

        recordValue("gateway.catalog.view.capabilities",
                newView.capabilities().size(), "active");
        recordValue("gateway.catalog.index.bytes",
                newIndex == null ? 0L : newIndex.sizeBytes(), "active");
        recordValue("gateway.catalog.index.documents",
                newIndex == null ? 0L : newIndex.documentCount(), "active");
        outcome = "activated";
        incrementRefresh(outcome);
        recordRefreshDuration(started, outcome);

        log.info("Catalog snapshot activated: version={}, capabilities={}, environment={}",
                newSnapshot.snapshotVersion(), newSnapshot.capabilities().size(), environment);
        return true;
    }

    private boolean buildBudgetExceeded(long started, CatalogSnapshot snapshot,
                                         long indexBytes) {
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        if (elapsedMs > buildTimeoutMs) {
            incrementRefresh("build_timeout");
            recordRefreshDuration(started, "build_timeout");
            log.warn("Catalog activation rejected: build duration={}ms exceeds limit={}ms",
                    elapsedMs, buildTimeoutMs);
            return true;
        }
        long residentIndexBytes = indexBytesOf(activeView.get())
                + indexBytesOf(retiredView.get());
        long processMemoryBytes = usedProcessMemoryBytes();
        long estimatedBytes = estimateSnapshotBytes(snapshot) + indexBytes
                + residentIndexBytes + processMemoryBytes;
        recordValue("gateway.catalog.process-memory.used", processMemoryBytes, "process");
        if (estimatedBytes > maxProcessMemoryBytes) {
            incrementRefresh("process_memory_capacity_rejected");
            recordRefreshDuration(started, "process_memory_capacity_rejected");
            log.warn("Catalog activation rejected: estimatedProcessBytes={} exceeds limit={}",
                    estimatedBytes, maxProcessMemoryBytes);
            return true;
        }
        return false;
    }

    private static long usedProcessMemoryBytes() {
        long used = 0L;
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        if (heap != null && heap.getUsed() > 0L) {
            used += heap.getUsed();
        }
        if (nonHeap != null && nonHeap.getUsed() > 0L) {
            used += nonHeap.getUsed();
        }
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(
                BufferPoolMXBean.class)) {
            String name = pool.getName();
            if (("direct".equalsIgnoreCase(name) || name.toLowerCase().contains("mapped"))
                    && pool.getMemoryUsed() > 0L) {
                used += pool.getMemoryUsed();
            }
        }
        return used;
    }

    private static long indexBytesOf(ActiveCatalogView view) {
        if (view == null || view.indexHandle() == null) {
            return 0L;
        }
        return view.indexHandle().sizeBytes();
    }

    private static long estimateSnapshotBytes(CatalogSnapshot snapshot) {
        long bytes = 256L + utf8Bytes(snapshot.environment())
                + utf8Bytes(snapshot.policyRef()) + utf8Bytes(snapshot.digest());
        for (CapabilityManifest capability : snapshot.capabilities()) {
            bytes += 512L + utf8Bytes(capability.toString());
        }
        return bytes;
    }

    private static long utf8Bytes(String value) {
        return value == null ? 0L
                : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private void incrementRefresh(String outcome) {
        if (telemetry != null) {
            telemetry.increment("gateway.catalog.refresh", Map.of("outcome", outcome));
        }
    }

    private void recordRefreshDuration(long started, String outcome) {
        if (telemetry != null) {
            telemetry.recordDuration("gateway.catalog.refresh.duration",
                    System.nanoTime() - started, Map.of("outcome", outcome));
        }
    }

    private void recordValue(String metric, long value, String resource) {
        if (telemetry != null) {
            telemetry.recordValue(metric, value, Map.of("resource", resource));
        }
    }

    /**
     * Returns the current active snapshot.
     *
     * <p>Each request is pinned to the snapshot version active at the start
     * of processing.</p>
     *
     * @return the current catalog snapshot, or {@code null} if no snapshot
     * has been loaded
     */
    public CatalogSnapshot getCurrentSnapshot() {
        ActiveCatalogView view = activeView.get();
        return view == null ? null : view.snapshot();
    }

    /** Returns the immutable runtime view pinned by a request. */
    public ActiveCatalogView getActiveView() {
        return activeView.get();
    }

    /** Returns a request-pinned active view, or {@code null} when unavailable. */
    public ActiveCatalogView.ViewLease acquireActiveView() {
        ActiveCatalogView view = activeView.get();
        if (view == null) {
            return null;
        }
        ActiveCatalogView.ViewLease lease = view.acquireLease();
        if (lease != null) {
            return lease;
        }

        // The view may have retired between the atomic read and the lease attempt.
        // Re-read the active generation once to avoid reporting a transient outage.
        ActiveCatalogView currentView = activeView.get();
        return currentView == null ? null : currentView.acquireLease();
    }

    /**
     * Finds a specific capability by ID and version within the current
     * snapshot.
     *
     * @param id the capability identifier
     * @param version the semantic version
     * @return the matching capability manifest, or empty if not found
     */
    public Optional<CapabilityManifest> findCapability(String id, String version) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        ActiveCatalogView view = activeView.get();
        return view == null ? Optional.empty() : view.find(id, version);
    }

    /**
     * Returns the current snapshot version for health check purposes
     *
     * @return the current snapshot version, or 0 if no snapshot is loaded
     */
    public long getCurrentSnapshotVersion() {
        ActiveCatalogView view = activeView.get();
        return view != null ? view.catalogVersion() : 0;
    }

    /**
     * Checks whether this instance's snapshot is stale — i.e., has not been
     * refreshed within the specified threshold.
     *
     * <p>After exceeding the maximum lag time, the instance should exit the
     * ready state. This method supports health check endpoints in determining
     * readiness.</p>
     *
     * @param maxLagMillis the maximum allowed lag time in milliseconds
     * @return {@code true} if the snapshot is stale (lag exceeds threshold
     * or no snapshot has been loaded)
     */
    public boolean isStale(long maxLagMillis) {
        Long lastLoad = lastLoadTime.get();
        if (lastLoad == 0L) {
            return true; // No snapshot ever loaded
        }
        long lag = System.currentTimeMillis() - lastLoad;
        return lag > maxLagMillis;
    }

    /**
     * Computes the content SHA-256 digest of the snapshot for integrity
     * verification.
     *
     * @param snapshot the snapshot to verify
     * @return the hex-encoded SHA-256 digest
     */
    private String computeSnapshotDigest(CatalogSnapshot snapshot) {
        return CatalogSnapshotDigest.sha256(snapshot);
    }
}
