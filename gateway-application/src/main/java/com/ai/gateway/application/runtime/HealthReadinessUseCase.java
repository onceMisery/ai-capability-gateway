package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.SecretManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Application owner for readiness checks used by the HTTP probe. */
public final class HealthReadinessUseCase {

    private final ManifestRepository manifestRepository;
    private final CatalogPort catalogPort;
    private final SecretManager secretManager;
    private final String environment;

    public HealthReadinessUseCase(ManifestRepository manifestRepository,
                                  CatalogPort catalogPort,
                                  SecretManager secretManager,
                                  String environment) {
        this.manifestRepository = Objects.requireNonNull(manifestRepository);
        this.catalogPort = Objects.requireNonNull(catalogPort);
        this.secretManager = Objects.requireNonNull(secretManager);
        this.environment = Objects.requireNonNull(environment);
    }

    public Result check() {
        Map<String, String> checks = new LinkedHashMap<>();
        boolean database = checkDatabase();
        checks.put("database", database ? "UP" : "DOWN");
        boolean snapshot = checkSnapshot();
        checks.put("activeSnapshot", snapshot ? "UP" : "DOWN");
        boolean secrets = checkSecrets();
        checks.put("requiredSecrets", secrets ? "UP" : "DOWN");
        boolean adapters = database && snapshot;
        checks.put("adapterInitialization", adapters ? "UP" : "DOWN");
        return new Result(database && snapshot && secrets && adapters, checks);
    }

    private boolean checkDatabase() {
        try {
            manifestRepository.findAll();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean checkSnapshot() {
        try {
            var snapshot = catalogPort.loadCurrentSnapshot(environment);
            return snapshot != null && snapshot.snapshotVersion() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean checkSecrets() {
        if (!"production".equalsIgnoreCase(environment)) {
            return true;
        }
        try {
            return !secretManager.getSecret("DB_PASSWORD").isBlank()
                    && !secretManager.getSecret("LLM_API_KEY").isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public record Result(boolean ready, Map<String, String> checks) {
        public Result {
            checks = Map.copyOf(checks);
        }
    }
}
