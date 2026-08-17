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
 * Conditional Spring wiring for the Redis (Redisson) infrastructure
 * components.
 *
 * <p>Activated only when {@code gateway.cache.provider=redis}. When inactive,
 * the gateway falls back to the logging {@code SnapshotNotifier} stub and the
 * direct PostgreSQL {@code CatalogPort} — no Redis dependency at runtime.</p>
 *
 * <p>Provides the three milestone-M2 building blocks from the
 * tech-selection doc §4: the pub/sub {@link SnapshotNotifier}, the
 * after-commit {@link CatalogPort} cache decorator (Redis L2 + Caffeine L1),
 * and the {@link SnapshotCacheListener} that hot-reloads snapshots on
 * notification. The same {@link RedissonClient} is reused by milestone M4
 * (distributed locks).</p>
 *
 * @since 0.1.0
 */
@Configuration
@ConditionalOnProperty(name = "gateway.cache.provider", havingValue = "redis")
@EnableConfigurationProperties(RedisGatewayProperties.class)
public class RedisCacheConfiguration {

    /**
     * Creates the shared {@link RedissonClient} using programmatic
     * single-server configuration (no Spring Boot starter).
     *
     * @param address the Redis address (e.g., {@code redis://127.0.0.1:6379})
     * @param password the Redis password (empty for none)
     * @param database the Redis database index
     * @return the Redisson client
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
     * ObjectMapper for snapshot JSON serialization into Redis String values.
     *
     * @return a lenient object mapper
     */
    @Bean
    public ObjectMapper redisSnapshotObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * The pub/sub {@link SnapshotNotifier}.
     *
     * @param redissonClient the Redisson client
     * @return the Redis-backed snapshot notifier
     */
    @Bean
    public SnapshotNotifier snapshotNotifier(RedissonClient redissonClient) {
        return new RedisSnapshotNotifier(redissonClient);
    }

    /**
     * The after-commit {@link CatalogPort} cache decorator wrapping the
     * PostgreSQL catalog port.
     *
     * @param postgresCatalogPort the PostgreSQL catalog port (qualified)
     * @param redissonClient the Redisson client
     * @param redisSnapshotObjectMapper the snapshot object mapper
     * @param localTtlSeconds the Caffeine L1 TTL in seconds
     * @return the caching catalog port decorator
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
     * The pub/sub listener that hot-reloads the in-memory snapshot and
     * rebuilds the retrieval index on notification.
     *
     * @param redissonClient the Redisson client
     * @param catalogManager the in-memory catalog manager
     * @param candidateRetriever the retrieval index rebuilder
     * @param environment the environment this instance serves
     * @return the snapshot cache listener
     */
    @Bean
    public SnapshotCacheListener snapshotCacheListener(
            RedissonClient redissonClient,
            InMemoryCatalogManager catalogManager,
            LuceneCandidateRetriever candidateRetriever,
            RedisGatewayProperties properties) {
        return new SnapshotCacheListener(
                redissonClient, catalogManager, candidateRetriever, properties.getEnvironment());
    }

    /**
     * The Redisson {@code RLock}-backed {@link DistributedLockPort}
     * (milestone M4).
     *
     * @param redissonClient the Redisson client
     * @return the distributed lock adapter
     */
    @Bean
    public DistributedLockPort distributedLockPort(RedissonClient redissonClient) {
        return new RedissonDistributedLockAdapter(redissonClient);
    }
}
