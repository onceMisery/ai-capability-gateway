package com.ai.gateway.bootstrap;

import com.ai.gateway.bootstrap.config.AuthProviderConfiguration;
import com.ai.gateway.bootstrap.config.CatalogProviderConfiguration;
import com.ai.gateway.bootstrap.config.DubboAdaptersConfiguration;
import com.ai.gateway.bootstrap.config.LlmAdaptersConfiguration;
import com.ai.gateway.bootstrap.config.PostgresqlAdaptersConfiguration;
import com.ai.gateway.bootstrap.config.RateLimitProviderConfiguration;
import com.ai.gateway.bootstrap.config.WebAdaptersConfiguration;
import cn.dev33.satoken.dao.SaTokenDaoRedissonJackson;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the AI Capability Gateway.
 *
 * <p>This class assembles all adapters and use cases without relying on
 * broad component scanning. The {@code @SpringBootApplication} annotation
 * scans only the {@code com.ai.gateway.bootstrap} package (this class's
 * package and sub-packages) for bootstrap-local {@code @Component} and
 * {@code @Configuration} classes such as {@link
 * com.ai.gateway.bootstrap.config.BeanConfig BeanConfig}, {@link
 * com.ai.gateway.bootstrap.config.SchemaValidatorAdapter SchemaValidatorAdapter},
 * {@link com.ai.gateway.bootstrap.config.SecretManagerAdapter SecretManagerAdapter},
 * and {@link com.ai.gateway.bootstrap.config.GatewayHealthIndicator
 * GatewayHealthIndicator}.</p>
 *
 * <p>Adapter implementations living in other packages are brought in
 * explicitly via {@link Import}, grouped by adapter domain into dedicated
 * configuration classes under {@code com.ai.gateway.bootstrap.config}:
 * pluggable providers (auth, catalog, rate limiting) and adapter groups
 * (PostgreSQL, Dubbo, LLM HTTP, Web). The application layer stays
 * framework-free — the bootstrap module is the single place where adapter
 * classes are wired to application use cases.</p>
 *
 * <p>{@link EnableScheduling} activates scheduled tasks such as the
 * outbox relay and data-retention cleanup.</p>
 *
 * @since 0.1.0
 */
@SpringBootApplication(exclude = SaTokenDaoRedissonJackson.class)
@EnableScheduling
@EnableConfigurationProperties({
        com.ai.gateway.bootstrap.config.GatewayProperties.class,
        com.ai.gateway.bootstrap.config.PayloadLimitsProperties.class
})
@Import({
        // --- Pluggable provider wiring (auth / catalog / rate limiting) ---
        // Each provider group contains mutually exclusive implementations
        // activated by their own @ConditionalOnProperty annotations.
        AuthProviderConfiguration.class,
        CatalogProviderConfiguration.class,
        RateLimitProviderConfiguration.class,

        // --- Adapter-layer wiring (grouped by adapter domain) ---
        PostgresqlAdaptersConfiguration.class,
        DubboAdaptersConfiguration.class,
        LlmAdaptersConfiguration.class,
        WebAdaptersConfiguration.class,
})
public class AiCapabilityGatewayApplication {

    /**
     * Boots the AI Capability Gateway Spring Boot application.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(AiCapabilityGatewayApplication.class, args);
    }
}
