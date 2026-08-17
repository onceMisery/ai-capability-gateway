package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.port.SecretManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bootstrap adapter implementing {@link SecretManager}.
 *
 * <p>This adapter resolves secrets in two layers:</p>
 * <ol>
 * <li><b>Environment variables</b> — checked first. The key is
 * transformed to uppercase with underscores (e.g.,
 * {@code "db.password"} becomes {@code "DB_PASSWORD"}).</li>
 * <li><b>Secret file</b> — if the environment variable is not set, the
 * adapter reads from a properties file located at
 * {@code gateway.secret-file-path} (if configured). The file uses
 * {@code key=value} format.</li>
 * </ol>
 *
 * <p>Production deployments should use a dedicated Secret Manager (e.g.,
 * HashiCorp Vault, Kubernetes Secrets) or Workload Identity. Configuration
 * files and Manifests must never contain secrets.</p>
 *
 * @since 0.1.0
 */
@Component
public class SecretManagerAdapter implements SecretManager {

    private static final Logger log = LoggerFactory.getLogger(SecretManagerAdapter.class);

    private final Path secretFilePath;
    private final Map<String, String> fileSecrets;

    /**
     * Constructs a new SecretManagerAdapter.
     *
     * @param secretFilePath the path to the secret properties file, resolved
     * from {@code gateway.secret-file-path}; may be
     * empty or {@code null} if no file is configured
     */
    public SecretManagerAdapter(GatewayProperties properties) {
        String secretFilePath = properties.getSecretFilePath();
        this.secretFilePath = (secretFilePath != null && !secretFilePath.isBlank())
                ? Paths.get(secretFilePath) : null;
        this.fileSecrets = loadSecretFile();
        log.info("SecretManagerAdapter initialized: secretFile={}",
                this.secretFilePath != null ? this.secretFilePath : "<none>");
    }

    @Override
    public String getSecret(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("secret key must not be null or blank");
        }

        // Layer 1: environment variable
        String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        // Also try the original key as an environment variable
        envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        // Layer 2: secret file
        String fileValue = fileSecrets.get(key);
        if (fileValue != null && !fileValue.isBlank()) {
            return fileValue;
        }

        throw new IllegalStateException(
                "Secret not found for key: " + key + " (checked env var " + envKey
                        + " and secret file " + secretFilePath + ")");
    }

    /**
     * Loads the secret properties file into a map.
     *
     * <p>If the file does not exist or cannot be read, an empty map is
     * returned and a warning is logged. This allows the gateway to start
     * even without a secret file, relying solely on environment variables.</p>
     *
     * @return an immutable map of secret key-value pairs
     */
    private Map<String, String> loadSecretFile() {
        if (secretFilePath == null) {
            return Map.of();
        }

        if (!Files.exists(secretFilePath)) {
            log.warn("Secret file not found: {}", secretFilePath);
            return Map.of();
        }

        try {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(secretFilePath)) {
                properties.load(reader);
            }

            Map<String, String> secrets = new ConcurrentHashMap<>();
            for (String name : properties.stringPropertyNames()) {
                secrets.put(name, properties.getProperty(name));
            }

            log.info("Loaded {} secrets from file: {}", secrets.size(), secretFilePath);
            return Map.copyOf(secrets);

        } catch (IOException e) {
            log.error("Failed to load secret file: {} — {}", secretFilePath, e.getMessage(), e);
            return Map.of();
        }
    }
}
