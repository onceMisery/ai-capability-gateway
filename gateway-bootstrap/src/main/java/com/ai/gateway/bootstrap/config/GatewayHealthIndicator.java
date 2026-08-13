package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.SecretManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom health indicator for gateway readiness checks.
 *
 * <p>This indicator contributes to the {@code /actuator/health} endpoint
 * under the {@code gateway-readiness} component name. It performs four
 * checks:</p>
 * <ol>
 * <li><b>Database connectivity</b> — verifies that a JDBC connection
 * can be obtained from the {@link DataSource}.</li>
 * <li><b>Active snapshot loaded</b> — verifies that the {@link CatalogPort}
 * can load the current production snapshot.</li>
 * <li><b>Required secrets present</b> — verifies that critical secrets
 * (e.g., database credentials, LLM API key) are resolvable via
 * {@link SecretManager}.</li>
 * <li><b>Adapter initialization</b> — verifies that key adapter beans
 * (catalog port, secret manager) are initialized and non-null.</li>
 * </ol>
 *
 * <p>If any check fails, the overall status is {@code DOWN} with details
 * indicating which checks failed.</p>
 *
 * @since 0.1.0
 */
@Component("gateway-readiness")
public class GatewayHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(GatewayHealthIndicator.class);

    private final DataSource dataSource;
    private final CatalogPort catalogPort;
    private final String environment;
    private final SecretManager secretManager;
    private volatile boolean adaptersInitialized;

    /**
     * Constructs a new GatewayHealthIndicator.
     *
     * @param dataSource the JDBC data source for database connectivity checks
     * @param catalogPort the catalog port for snapshot checks
     * @param secretManager the secret manager for secret presence checks
     */
    public GatewayHealthIndicator(DataSource dataSource,
                                  CatalogPort catalogPort,
                                  SecretManager secretManager) {
        this.dataSource = dataSource;
        this.catalogPort = catalogPort;
        this.environment = "production";
        this.secretManager = secretManager;
        this.adaptersInitialized = true;
        log.info("GatewayHealthIndicator initialized");
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean allHealthy = true;

        // Check 1: Database connectivity
        boolean dbOk = checkDatabase(details);
        if (!dbOk) {
            allHealthy = false;
        }

        // Check 2: Active snapshot loaded
        boolean snapshotOk = checkSnapshot(details);
        if (!snapshotOk) {
            allHealthy = false;
        }

        // Check 3: Required secrets present
        boolean secretsOk = checkSecrets(details);
        if (!secretsOk) {
            allHealthy = false;
        }

        // Check 4: Adapter initialization status
        boolean adaptersOk = checkAdapters(details);
        if (!adaptersOk) {
            allHealthy = false;
        }

        if (allHealthy) {
            return Health.up()
                    .withDetails(details)
                    .build();
        } else {
            return Health.down()
                    .withDetails(details)
                    .build();
        }
    }

    /**
     * Checks database connectivity by obtaining a JDBC connection.
     *
     * @param details the details map to populate with check results
     * @return {@code true} if the database is reachable
     */
    private boolean checkDatabase(Map<String, Object> details) {
        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(5);
            details.put("database", valid ? "UP" : "DOWN");
            if (!valid) {
                log.warn("Health check: database connection is not valid");
            }
            return valid;
        } catch (Exception e) {
            details.put("database", "DOWN: " + e.getMessage());
            log.warn("Health check: database connectivity failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks that the active catalog snapshot is loaded.
     *
     * @param details the details map to populate with check results
     * @return {@code true} if the snapshot is loaded
     */
    private boolean checkSnapshot(Map<String, Object> details) {
        try {
            var snapshot = catalogPort.loadCurrentSnapshot(environment);
            if (snapshot != null) {
                details.put("snapshot", "UP (version=" + snapshot.snapshotVersion() + ")");
                return true;
            } else {
                details.put("snapshot", "DOWN (null snapshot)");
                log.warn("Health check: catalog snapshot is null");
                return false;
            }
        } catch (Exception e) {
            details.put("snapshot", "DOWN: " + e.getMessage());
            log.warn("Health check: snapshot loading failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks that required secrets are present.
     *
     * @param details the details map to populate with check results
     * @return {@code true} if all required secrets are resolvable
     */
    private boolean checkSecrets(Map<String, Object> details) {
        String[] requiredSecrets = {"DB_PASSWORD", "LLM_API_KEY"};
        boolean allPresent = true;
        Map<String, String> secretStatus = new LinkedHashMap<>();

        for (String secretKey : requiredSecrets) {
            try {
                secretManager.getSecret(secretKey);
                secretStatus.put(secretKey, "present");
            } catch (Exception e) {
                secretStatus.put(secretKey, "missing");
                allPresent = false;
                log.warn("Health check: secret '{}' not found: {}", secretKey, e.getMessage());
            }
        }

        details.put("secrets", secretStatus);
        return allPresent;
    }

    /**
     * Checks that adapter beans are initialized.
     *
     * @param details the details map to populate with check results
     * @return {@code true} if all adapters are initialized
     */
    private boolean checkAdapters(Map<String, Object> details) {
        boolean catalogOk = catalogPort != null;
        boolean secretOk = secretManager != null;
        boolean initOk = adaptersInitialized;

        Map<String, String> adapterStatus = new LinkedHashMap<>();
        adapterStatus.put("catalogPort", catalogOk ? "initialized" : "null");
        adapterStatus.put("secretManager", secretOk ? "initialized" : "null");
        adapterStatus.put("initialized", initOk ? "true" : "false");

        details.put("adapters", adapterStatus);
        return catalogOk && secretOk && initOk;
    }
}
