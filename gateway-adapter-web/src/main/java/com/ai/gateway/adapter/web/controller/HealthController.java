package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.SecretManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

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

    private final CatalogPort catalogPort;
    private final String environment;
    private final ManifestRepository manifestRepository;
    private final SecretManager secretManager;

    /**
     * Constructs a new HealthController.
     *
     * @param catalogPort the catalog port for snapshot readiness checks
     * @param manifestRepository the manifest repository for database connectivity checks
     * @throws NullPointerException if any argument is null
     */
    public HealthController(CatalogPort catalogPort,
                             ManifestRepository manifestRepository) {
        this(catalogPort, manifestRepository, key -> "legacy-config", "production");
    }

    @Autowired
    public HealthController(CatalogPort catalogPort,
                            ManifestRepository manifestRepository,
                            SecretManager secretManager,
                            @Value("${gateway.environment:production}") String environment) {
        this.catalogPort = Objects.requireNonNull(catalogPort,
                "catalogPort must not be null");
        this.manifestRepository = Objects.requireNonNull(manifestRepository,
                "manifestRepository must not be null");
        this.secretManager = Objects.requireNonNull(secretManager,
                "secretManager must not be null");
        this.environment = Objects.requireNonNull(environment,
                "environment must not be null");
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
        Map<String, Object> checks = new LinkedHashMap<>();
        boolean allHealthy = true;

        // Check 1: Database connectivity
        boolean dbHealthy = checkDatabaseConnectivity();
        checks.put("database", dbHealthy ? "UP" : "DOWN");
        if (!dbHealthy) {
            allHealthy = false;
        }

        // Check 2: Active snapshot loaded
        boolean snapshotHealthy = checkActiveSnapshot();
        checks.put("activeSnapshot", snapshotHealthy ? "UP" : "DOWN");
        if (!snapshotHealthy) {
            allHealthy = false;
        }

        boolean secretsHealthy = checkRequiredSecrets();
        checks.put("requiredSecrets", secretsHealthy ? "UP" : "DOWN");
        if (!secretsHealthy) {
            allHealthy = false;
        }

        // Check 4: Adapter initialization
        boolean adapterHealthy = checkAdapterInitialization();
        checks.put("adapterInitialization", adapterHealthy ? "UP" : "DOWN");
        if (!adapterHealthy) {
            allHealthy = false;
        }

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

    /**
     * Checks database connectivity by querying the manifest repository.
     *
     * @return true if the database is reachable
     */
    private boolean checkDatabaseConnectivity() {
        try {
            manifestRepository.findAll();
            return true;
        } catch (Exception e) {
            log.warn("Database connectivity check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks whether an active catalog snapshot is loaded for production.
     *
     * @return true if a production snapshot exists
     */
    private boolean checkActiveSnapshot() {
        try {
            var snapshot = catalogPort.loadCurrentSnapshot(environment);
            return snapshot != null;
        } catch (Exception e) {
            log.warn("Active snapshot check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks whether the adapter (catalog port) is initialized and functional.
     *
     * @return true if the adapter is initialized
     */
    private boolean checkAdapterInitialization() {
        try {
            // A simple null check and method invocation confirms initialization.
            // In a full implementation, this would verify that all protocol
            // adapters (e.g., DubboInvocationAdapter) are initialized and
            // their registries are connected.
            catalogPort.loadCurrentSnapshot(environment);
            return true;
        } catch (Exception e) {
            log.warn("Adapter initialization check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkRequiredSecrets() {
        if (!"production".equalsIgnoreCase(environment)) {
            return true;
        }
        try {
            return !secretManager.getSecret("DB_PASSWORD").isBlank()
                    && !secretManager.getSecret("LLM_API_KEY").isBlank();
        } catch (RuntimeException e) {
            log.warn("Required secret check failed: {}", e.getMessage());
            return false;
        }
    }
}
