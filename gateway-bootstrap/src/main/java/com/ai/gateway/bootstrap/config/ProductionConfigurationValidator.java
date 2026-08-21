package com.ai.gateway.bootstrap.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 当部署环境不明确，或生产部署仍沿用开发期默认配置时，
 * 使应用启动失败。
 *
 * @author cmiracle@163.com
 */
@Component
public final class ProductionConfigurationValidator implements InitializingBean {

    private static final Set<String> ALLOWED_ENVIRONMENTS =
            Set.of("development", "test", "staging", "production");
    private static final int MIN_SECRET_BYTES = 32;

    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate(environment);
    }

    static void validate(Environment environment) {
        String deploymentEnvironment = value(environment, "gateway.environment");
        if (deploymentEnvironment.isBlank()) {
            throw new IllegalStateException(
                    "gateway.environment must be explicitly configured");
        }

        String normalized = deploymentEnvironment.toLowerCase(Locale.ROOT);
        if (!ALLOWED_ENVIRONMENTS.contains(normalized)) {
            throw new IllegalStateException(
                    "gateway.environment must be one of " + ALLOWED_ENVIRONMENTS);
        }
        if (!"production".equals(normalized)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        requireEquals(environment, violations, "gateway.auth.provider", "sa-token");
        requireEquals(environment, violations, "gateway.cache.provider", "redis");
        requireEquals(environment, violations, "gateway.ratelimit.provider", "sentinel");

        requireNonBlank(environment, violations, "spring.datasource.url");
        requireNonBlank(environment, violations, "spring.datasource.username");
        requireNonBlank(environment, violations, "spring.datasource.password");
        requireNonBlank(environment, violations, "gateway.redis.address");
        requireNonBlank(environment, violations, "gateway.llm.endpoint");
        requireNonBlank(environment, violations, "gateway.llm.api-key");
        requireNonBlank(environment, violations, "gateway.llm.model");
        requireNonBlank(environment, violations, "dubbo.registry.address");

        String datasourceUrl = value(environment, "spring.datasource.url");
        if (!datasourceUrl.startsWith("jdbc:postgresql:") || datasourceUrl.endsWith("://")) {
            violations.add("spring.datasource.url must be a concrete PostgreSQL JDBC URL");
        }

        requireStrongSecret(environment, violations,
                "gateway.auth.sa-token.jwt-secret-key");
        requireStrongSecret(environment, violations,
                "gateway.operation.confirmation-secret");
        requireStrongSecret(environment, violations,
                "gateway.agent.tool-ref-secret");

        String adminUsername = value(environment, "gateway.auth.console-admin.username");
        String adminPassword = value(environment, "gateway.auth.console-admin.password");
        if (adminUsername.isBlank() || adminPassword.isBlank()
                || "admin".equalsIgnoreCase(adminPassword)
                || "change-me".equalsIgnoreCase(adminPassword)
                || adminPassword.length() < 12) {
            violations.add("gateway.auth.console-admin username/password must be explicitly "
                    + "configured and the password must contain at least 12 characters");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe production configuration: " + String.join("; ", violations));
        }
    }

    private static void requireEquals(Environment environment, List<String> violations,
                                      String key, String expected) {
        String actual = value(environment, key);
        if (!expected.equalsIgnoreCase(actual)) {
            violations.add(key + " must be '" + expected + "'");
        }
    }

    private static void requireNonBlank(Environment environment, List<String> violations,
                                        String key) {
        if (value(environment, key).isBlank()) {
            violations.add(key + " is required");
        }
    }

    private static void requireStrongSecret(Environment environment, List<String> violations,
                                            String key) {
        String secret = value(environment, key);
        String normalized = secret.toLowerCase(Locale.ROOT);
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES
                || normalized.contains("dev-only")
                || normalized.contains("change-me")) {
            violations.add(key + " must contain at least " + MIN_SECRET_BYTES
                    + " bytes and must not use a development default");
        }
    }

    private static String value(Environment environment, String key) {
        String value = environment.getProperty(key);
        return value == null ? "" : value.trim();
    }
}
