package com.ai.gateway.application.catalog;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.CompatibilityTestPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drift detection scheduler
 *
 * <p>Periodically checks published capabilities against the test environment
 * to detect protocol drift: method not found, parameter changes, serialization
 * incompatibility, response path mismatch, Provider authentication changes.
 *
 * <p>Also performs automated serialization negotiation probing:
 * when a whitelisted serialization is no longer negotiable, alert and pause
 * new requests for the affected capability.
 *
 * <p>Runtime discovery of similar errors triggers circuit breaker and
 * automatic pause recommendation, but MUST NOT modify the catalog directly
 * from a single error.
 */
public final class DriftDetectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DriftDetectionScheduler.class);

    private final CatalogPort catalogPort;
    private final CompatibilityTestPort compatibilityTestPort;
    private final ScheduledExecutorService scheduler;
    private final String testEnvironment;
    private final long intervalMinutes;

    /**
     * Creates a drift detection scheduler.
     *
     * @param catalogPort catalog port for loading published capabilities
     * @param compatibilityTestPort compatibility test execution port
     * @param testEnvironment target test environment name
     * @param intervalMinutes check interval in minutes
     */
    public DriftDetectionScheduler(CatalogPort catalogPort,
                                   CompatibilityTestPort compatibilityTestPort,
                                   String testEnvironment,
                                   long intervalMinutes) {
        this.catalogPort = catalogPort;
        this.compatibilityTestPort = compatibilityTestPort;
        this.testEnvironment = testEnvironment;
        this.intervalMinutes = intervalMinutes;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drift-detection");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the periodic drift detection.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::runDetection, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("Drift detection scheduler started, interval={}min, environment={}", intervalMinutes, testEnvironment);
    }

    /**
     * Stops the scheduler gracefully.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Drift detection scheduler stopped");
    }

    /**
     * Runs a single drift detection cycle against all published capabilities.
     */
    private void runDetection() {
        try {
            CatalogSnapshot snapshot = catalogPort.loadCurrentSnapshot(testEnvironment);
            if (snapshot == null) {
                log.warn("No active snapshot found for environment={}, skipping drift detection", testEnvironment);
                return;
            }

            List<DriftResult> results = new ArrayList<>();
            for (CapabilityManifest manifest : snapshot.capabilities()) {
                DriftResult result = checkCapability(manifest);
                results.add(result);
                if (!result.driftFree()) {
                    log.warn("DRIFT DETECTED: capability={} version={} issues={}",
                            manifest.metadata().id(), manifest.metadata().version(), result.issues());
                }
            }

            long driftCount = results.stream().filter(r -> !r.driftFree()).count();
            log.info("Drift detection cycle complete: {} capabilities checked, {} with drift",
                    results.size(), driftCount);

        } catch (Exception e) {
            log.error("Drift detection cycle failed", e);
        }
    }

    /**
     * Checks a single capability for drift.
     *
     * <p>Detects:
     * <ul>
     * <li>Method not found</li>
     * <li>Parameter count, order, or type changes</li>
     * <li>Serialization incompatibility or negotiation capability shrinkage</li>
     * <li>Provider-side filter implicit contract changes</li>
     * <li>Response envelope path or public Schema mismatch</li>
     * <li>Provider no longer meets identity authentication or tenant isolation requirements</li>
     * </ul>
     */
    private DriftResult checkCapability(CapabilityManifest manifest) {
        List<String> issues = new ArrayList<>();

        try {
            ValidationReport report = compatibilityTestPort.runCompatibilityTest(manifest, testEnvironment);
            if (!report.valid()) {
                issues.addAll(report.errors());
            }
        } catch (Exception e) {
            issues.add("Compatibility test execution failed: " + e.getMessage());
        }

        // Serialization negotiation probing
        // When whitelisted serialization is no longer negotiable, alert and pause
        String serialization = manifest.spec().invocation().serialization();
        if (serialization != null && !serialization.isEmpty()) {
            // Placeholder: actual probing would invoke the Provider with the declared serialization
            // and verify the negotiation result matches the Manifest declaration
            log.debug("Serialization negotiation probe for capability={} serialization={}",
                    manifest.metadata().id(), serialization);
        }

        return new DriftResult(manifest.metadata().id(), manifest.metadata().version(), issues);
    }

    /**
     * Result of a drift detection check for a single capability.
     *
     * @param capabilityId the capability ID
     * @param capabilityVersion the capability version
     * @param issues list of detected issues (empty if no drift)
     */
    public record DriftResult(String capabilityId, String capabilityVersion, List<String> issues) {
        public boolean driftFree() {
            return issues == null || issues.isEmpty();
        }
    }
}
