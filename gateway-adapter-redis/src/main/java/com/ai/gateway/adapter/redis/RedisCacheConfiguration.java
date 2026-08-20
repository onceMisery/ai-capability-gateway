package com.ai.gateway.adapter.redis;

import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.application.catalog.LuceneCandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.DistributedLockPort;
import com.ai.gateway.domain.port.SnapshotNotifier;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Redis（Redisson）基础设施组件的 Spring 条件装配。
 *
 * <p>仅当 {@code gateway.cache.provider=redis} 时激活。未激活时，网关回退到日志桩
 * {@code SnapshotNotifier} 与直连 PostgreSQL 的 {@code CatalogPort}——运行时不依赖 Redis。</p>
 *
 * <p>提供技术选型文档 §4 中里程碑 M2 的三个构建块：pub/sub {@link SnapshotNotifier}、
 * 提交后（after-commit）的 {@link CatalogPort} 缓存装饰器（Redis L2 + Caffeine L1），
 * 以及收到通知时热加载快照的 {@link SnapshotCacheListener}。同一 {@link RedissonClient}
 * 由里程碑 M4（分布式锁）复用。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Configuration
@ConditionalOnProperty(name = "gateway.cache.provider", havingValue = "redis")
@EnableConfigurationProperties(RedisGatewayProperties.class)
public class RedisCacheConfiguration {

    /**
     * 使用编程式单服务器配置创建共享的 {@link RedissonClient}（不引入 Spring Boot starter）。
     *
     * @param address Redis 地址（例如 {@code redis://127.0.0.1:6379}）
     * @param password Redis 密码（无密码则留空）
     * @param database Redis 数据库索引
     * @return Redisson 客户端
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisGatewayProperties properties) {
        RedisGatewayProperties.Redis redis = properties.getRedis();
        Config config = new Config();
        var serverConfig = config.useSingleServer()
                .setAddress(redis.getAddress())
                .setDatabase(redis.getDatabase());
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            serverConfig.setPassword(redis.getPassword());
        }
        return Redisson.create(config);
    }

    /**
     * 用于将快照序列化为 Redis 字符串值的 ObjectMapper。
     *
     * @return 宽松模式的 object mapper
     */
    @Bean
    public ObjectMapper redisSnapshotObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * pub/sub 形式的 {@link SnapshotNotifier}。
     *
     * @param redissonClient Redisson 客户端
     * @return 基于 Redis 的快照通知器
     */
    @Bean
    public SnapshotNotifier snapshotNotifier(RedissonClient redissonClient) {
        return new RedisSnapshotNotifier(redissonClient);
    }

    /**
     * 包裹 PostgreSQL 目录端口、在事务提交后生效的 {@link CatalogPort} 缓存装饰器。
     *
     * @param postgresCatalogPort PostgreSQL 目录端口（限定名）
     * @param redissonClient Redisson 客户端
     * @param redisSnapshotObjectMapper 快照 object mapper
     * @param localTtlSeconds Caffeine L1 的 TTL（秒）
     * @return 带缓存的目录端口装饰器
     */
    @Bean
    @Primary
    public CatalogPort catalogPort(
            @Qualifier("postgresCatalogPort") CatalogPort postgresCatalogPort,
            RedissonClient redissonClient,
            ObjectMapper redisSnapshotObjectMapper,
            RedisGatewayProperties properties) {
        return new RedisCatalogPortDecorator(
                postgresCatalogPort, redissonClient, redisSnapshotObjectMapper,
                properties.getRedis().getSnapshot().getLocalTtlSeconds());
    }

    /**
     * 在收到通知时热加载内存快照并重建检索引擎的 pub/sub 监听器。
     *
     * @param redissonClient Redisson 客户端
     * @param catalogManager 内存目录管理器
     * @param environment 本实例所服务的环境
     * @return 快照缓存监听器
     */
    @Bean
    public SnapshotCacheListener snapshotCacheListener(
            RedissonClient redissonClient,
            InMemoryCatalogManager catalogManager,
            RedisGatewayProperties properties) {
        return new SnapshotCacheListener(
                redissonClient, catalogManager, properties.getEnvironment());
    }

    /**
     * 基于 Redisson {@code RLock} 的 {@link DistributedLockPort}（里程碑 M4）。
     *
     * @param redissonClient Redisson 客户端
     * @return 分布式锁适配器
     */
    @Bean
    public DistributedLockPort distributedLockPort(RedissonClient redissonClient) {
        return new RedissonDistributedLockAdapter(redissonClient);
    }
}
