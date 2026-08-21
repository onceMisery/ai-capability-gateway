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
 * 默认目录装配：直接注册 {@link JdbcCatalogPort}。
 *
 * <p>{@link JdbcCatalogPort} 始终被注册，并可通过 {@code postgresCatalogPort}
 * 限定符引用。当 {@code gateway.cache.provider=redis} 时，
 * {@link com.ai.gateway.adapter.redis.RedisCacheConfiguration} 会注册一个带缓存的
 * {@code RedisCatalogPortDecorator}（标注 {@code @Primary}）来包装
 * {@code postgresCatalogPort} 限定符。当缓存提供方为 {@code stub}（或未设置）时，
 * 本配置额外将 PostgreSQL 端口暴露为主 {@link CatalogPort}，使未限定的注入
 * 解析到它。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
@Import(JdbcCatalogPort.class)
@Slf4j
public class DirectCatalogConfiguration {

    /**
     * 仅在网关未启用 Redis 缓存层（stub 缓存提供方或未设置）时，将 PostgreSQL
     * 目录端口作为主 {@link CatalogPort} 暴露。当 {@code gateway.cache.provider=redis}
     * 时，来自 {@code RedisCacheConfiguration} 的 Redis 装饰器接管为主 Bean，
     * 并包装仍被注册的 {@code postgresCatalogPort}。
     *
     * @param postgresCatalogPort PostgreSQL 目录端口（限定符引用）
     * @return 同一个 PostgreSQL 目录端口，作为主 Bean
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
