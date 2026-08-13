package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.redis.RedisCacheConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 目录快照装配（可插拔，里程碑 M2）。
 *
 * <p>聚合目录端口的两套实现：</p>
 * <ul>
 * <li>{@link DirectCatalogConfiguration}：默认激活（{@code gateway.cache.provider}
 * 未设置或为 {@code stub}），直接注册 {@code JdbcCatalogPort}；</li>
 * <li>{@link RedisCacheConfiguration}：在 {@code gateway.cache.provider=redis}
 * 时激活，用 Redis 发布订阅通知 + 写穿透缓存装饰器包装目录端口。</li>
 * </ul>
 *
 * @since 0.1.0
 */
@Configuration
@Import({
        DirectCatalogConfiguration.class,
        RedisCacheConfiguration.class,
})
public class CatalogProviderConfiguration {
}
