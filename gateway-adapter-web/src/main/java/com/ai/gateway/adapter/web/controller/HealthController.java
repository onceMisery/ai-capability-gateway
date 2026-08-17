package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.runtime.HealthReadinessUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * REST controller for health check endpoints.
 *
 * <p>This controller exposes two health check endpoints:</p>
 * <ul>
 * <li>{@code GET /health/readiness} — checks whether the gateway is ready
 * to accept requests. Verifies: database connectivity, active snapshot
 * loaded, required secrets available, and adapter initialization.</li>
 * <li>{@code GET /health/liveness} — checks whether the process is alive.
 * This is a lightweight check that only verifies the process is
 * running and can respond to HTTP requests.</li>
 * </ul>
 *
 * <p>Readiness probes return HTTP 200 when all checks pass, or HTTP 503
 * when one or more checks fail. Liveness probes always return HTTP 200
 * as long as the process can respond.</p>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final HealthReadinessUseCase readinessUseCase;

    /**
     * Constructs a new HealthController.
     *
     * @param catalogPort the catalog port for snapshot readiness checks
     * @param manifestRepository the manifest repository for database connectivity checks
     * @throws NullPointerException if any argument is null
     */
    public HealthController(HealthReadinessUseCase readinessUseCase) {
        this.readinessUseCase = Objects.requireNonNull(readinessUseCase,
                "readinessUseCase must not be null");
    }

    /**
     * Readiness probe.
     *
     * <p>Checks the following conditions:</p>
     * <ol>
     * <li>Database status — verifies connectivity to the manifest repository.</li>
     * <li>Active snapshot loaded — verifies that a production snapshot exists.</li>
     * <li>Required secrets — verifies that necessary configuration is present
     * (placeholder for future secret checks).</li>
     * <li>Adapter initialization — verifies that the catalog port is functional.</li>
     * </ol>
     *
     * @return HTTP 200 with check results if all checks pass, HTTP 503 otherwise
     */
    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        HealthReadinessUseCase.Result readiness = readinessUseCase.check();
        Map<String, Object> checks = new LinkedHashMap<>(readiness.checks());
        boolean allHealthy = readiness.ready();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allHealthy ? "UP" : "DOWN");
        body.put("checks", checks);

        if (allHealthy) {
            return ResponseEntity.ok(body);
        } else {
            log.warn("Readiness check failed: {}", checks);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    /**
     * Liveness probe.
     *
     * <p>This is a lightweight check that only verifies the process is alive
     * and can respond to HTTP requests. It does not check downstream
     * dependencies.</p>
     *
     * @return HTTP 200 always, as long as the process can respond
     */
    @GetMapping("/liveness")
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(body);
    }

}
