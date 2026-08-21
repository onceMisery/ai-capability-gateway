package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.redis.RedisCacheConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 目录快照装配（可插拔，里程碑 M2）。
 *
 * <p>聚合目录端口的两套实现：</p>
 * <ul>
 * <li>{@link DirectCatalogConfiguration}：始终注册 {@code JdbcCatalogPort}
 * （限定符 {@code postgresCatalogPort}），并在 {@code gateway.cache.provider}
 * 为 {@code stub} 或未设置时将其作为主 {@code CatalogPort} 暴露；</li>
 * <li>{@link RedisCacheConfiguration}：在 {@code gateway.cache.provider=redis}
 * 时激活，用 Redis 发布订阅通知 + 写穿透缓存装饰器包装目录端口，并将装饰器作为主
 * {@code CatalogPort} 暴露。</li>
 * </ul>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
@Import({
        DirectCatalogConfiguration.class,
        RedisCacheConfiguration.class,
})
public class CatalogProviderConfiguration {
}
