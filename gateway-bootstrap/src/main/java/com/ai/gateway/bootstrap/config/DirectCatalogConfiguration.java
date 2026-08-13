package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.postgresql.repository.JdbcCatalogPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Default catalog wiring: registers {@link JdbcCatalogPort} directly as the
 * {@code CatalogPort} bean.
 *
 * <p>Active when {@code gateway.cache.provider} is unset or {@code stub}.
 * When {@code gateway.cache.provider=redis}, this configuration steps aside
 * and {@code RedisCacheConfiguration} registers a caching
 * {@code RedisCatalogPortDecorator} that wraps the (still {@code @Primary}
 * but qualifier-addressable) {@link JdbcCatalogPort}.</p>
 *
 * @since 0.1.0
 */
@Configuration
@ConditionalOnProperty(name = "gateway.cache.provider", havingValue = "stub", matchIfMissing = true)
@Import(JdbcCatalogPort.class)
public class DirectCatalogConfiguration {
}
