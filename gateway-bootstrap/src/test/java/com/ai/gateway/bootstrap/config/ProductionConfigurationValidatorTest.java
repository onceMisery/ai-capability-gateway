package com.ai.gateway.bootstrap.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProductionConfigurationValidatorTest 类。
 *
 * @author cmiracle@163.com
 */
class ProductionConfigurationValidatorTest {

    @Test
    void rejectsMissingExplicitEnvironment() {
        MockEnvironment environment = new MockEnvironment();

        assertThatThrownBy(() -> ProductionConfigurationValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.environment");
    }

    @Test
    void rejectsInsecureProductionDefaultsAndStubProviders() {
        MockEnvironment environment = productionEnvironment()
                .withProperty("gateway.auth.provider", "stub")
                .withProperty("gateway.cache.provider", "stub")
                .withProperty("gateway.ratelimit.provider", "stub")
                .withProperty("gateway.auth.sa-token.jwt-secret-key", "dev-only-insecure-secret-change-me")
                .withProperty("gateway.auth.console-admin.username", "admin")
                .withProperty("gateway.auth.console-admin.password", "admin");

        assertThatThrownBy(() -> ProductionConfigurationValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.provider")
                .hasMessageContaining("cache.provider")
                .hasMessageContaining("ratelimit.provider")
                .hasMessageContaining("jwt-secret-key")
                .hasMessageContaining("console-admin");
    }

    @Test
    void acceptsCompleteProductionConfiguration() {
        MockEnvironment environment = productionEnvironment();

        assertThatCode(() -> ProductionConfigurationValidator.validate(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsExplicitDevelopmentConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("gateway.environment", "development");

        assertThatCode(() -> ProductionConfigurationValidator.validate(environment))
                .doesNotThrowAnyException();
    }

    private static MockEnvironment productionEnvironment() {
        return new MockEnvironment()
                .withProperty("gateway.environment", "production")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db:5432/gateway")
                .withProperty("spring.datasource.username", "gateway")
                .withProperty("spring.datasource.password", "db-password")
                .withProperty("gateway.auth.provider", "sa-token")
                .withProperty("gateway.auth.sa-token.jwt-secret-key",
                        "a-production-jwt-secret-with-at-least-32-bytes")
                .withProperty("gateway.auth.console-admin.username", "gateway-admin")
                .withProperty("gateway.auth.console-admin.password", "a-strong-admin-password")
                .withProperty("gateway.cache.provider", "redis")
                .withProperty("gateway.redis.address", "redis://redis:6379")
                .withProperty("gateway.ratelimit.provider", "sentinel")
                .withProperty("gateway.llm.endpoint", "https://llm.example.com/v1")
                .withProperty("gateway.llm.api-key", "llm-secret")
                .withProperty("gateway.llm.model", "gateway-model")
                .withProperty("dubbo.registry.address", "nacos://nacos:8848")
                .withProperty("gateway.operation.confirmation-secret",
                        "a-production-confirm-secret-at-least-32-bytes")
                .withProperty("gateway.agent.tool-ref-secret",
                        "a-production-agent-tool-ref-secret-at-least-32-bytes");
    }
}
