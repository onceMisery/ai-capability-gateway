package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.postgresql.repository.JdbcCatalogPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.SnapshotNotifier;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    /**
     * 创建不依赖 Redis 的快照通知器。
     *
     * <p>该 Bean 归属于目录缓存策略，与认证提供方无关。因此
     * {@code sa-token + cache=stub} 和 {@code auth=stub + cache=stub}
     * 两种组合都可以正常启动。</p>
     *
     * @return 仅记录日志的快照通知器
     */
    @Bean
    @ConditionalOnProperty(name = "gateway.cache.provider", havingValue = "stub", matchIfMissing = true)
    public SnapshotNotifier snapshotNotifier() {
        return new SnapshotNotifier() {
            @Override
            public void notifySnapshotPublished(long snapshotVersion) {
                log.info("Snapshot published notification (stub): version={}", snapshotVersion);
            }

            @Override
            public void notifySnapshotSuspended(long snapshotVersion) {
                log.warn("Snapshot suspended notification (stub): version={}", snapshotVersion);
            }
        };
    }
}
