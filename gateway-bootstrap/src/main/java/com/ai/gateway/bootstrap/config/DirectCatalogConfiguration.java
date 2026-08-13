package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.postgresql.repository.JdbcCatalogPort;
import com.ai.gateway.domain.port.CatalogPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Default catalog wiring: registers {@link JdbcCatalogPort} directly.
 *
 * <p>{@link JdbcCatalogPort} is always registered and addressable via the
 * {@code postgresCatalogPort} qualifier. When {@code gateway.cache.provider=redis},
 * {@link com.ai.gateway.adapter.redis.RedisCacheConfiguration} registers a
 * caching {@code RedisCatalogPortDecorator} (marked {@code @Primary}) that wraps
 * the {@code postgresCatalogPort} qualifier. When the cache provider is
 * {@code stub} (or unset), this configuration additionally exposes the
 * PostgreSQL port as the primary {@link CatalogPort} so unqualified injections
 * resolve to it.</p>
 *
 * @since 0.1.0
 */
@Configuration
@Import(JdbcCatalogPort.class)
public class DirectCatalogConfiguration {

    /**
     * Exposes the PostgreSQL catalog port as the primary {@link CatalogPort}
     * only when the gateway runs without the Redis cache layer (stub cache
     * provider, or unset). When {@code gateway.cache.provider=redis} the Redis
     * decorator from {@code RedisCacheConfiguration} takes over as the primary
     * bean and wraps the still-registered {@code postgresCatalogPort}.
     *
     * @param postgresCatalogPort the PostgreSQL catalog port (qualified)
     * @return the same PostgreSQL catalog port, as the primary bean
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "gateway.cache.provider", havingValue = "stub", matchIfMissing = true)
    public CatalogPort catalogPort(@Qualifier("postgresCatalogPort") CatalogPort postgresCatalogPort) {
        return postgresCatalogPort;
    }
}
