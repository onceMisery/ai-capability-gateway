package com.ai.gateway.adapter.redis;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.SnapshotSummary;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link CatalogPort} 的 Redis 缓存装饰器。
 *
 * <p>实现技术选型文档 §4 所述的两级缓存：本地 Caffeine L1（短 TTL）前置 Redis L2，
 * 以 PostgreSQL 作为唯一事实来源。读取当前快照时依次查询 L1、L2，再回退到 PostgreSQL 并回填缓存。
 * 快照写入在发布事务提交前不会写入 Redis。</p>
 *
 * <p>历史快照读取与能力查找直接委派给 PostgreSQL——仅对热点路径 {@code loadCurrentSnapshot} 做缓存。
 * 任意 Redis 故障都会优雅降级到 PostgreSQL，确保缓存故障不会中断网关。</p>
 *
 * @author cmiracle@163.com
 * @see CatalogPort
 * @since 0.1.0
 */
@Slf4j
public class RedisCatalogPortDecorator implements CatalogPort {

    private final CatalogPort delegate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, CatalogSnapshot> localCache;

    /**
     * 构造新的装饰器。
     *
     * @param delegate 底层（PostgreSQL）目录端口
     * @param redissonClient 用于 L2 缓存的 Redisson 客户端
     * @param objectMapper 将快照序列化为 JSON 的映射器
     * @param localTtlSeconds Caffeine L1 的存活时间（秒）
     * @throws NullPointerException 任意参数为 {@code null} 时抛出
     */
    public RedisCatalogPortDecorator(CatalogPort delegate,
                                     RedissonClient redissonClient,
                                     ObjectMapper objectMapper,
                                     long localTtlSeconds) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.redissonClient = Objects.requireNonNull(redissonClient,
                "redissonClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.localCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(localTtlSeconds))
                .maximumSize(64)
                .build();
    }

    @Override
    public void lockEnvironmentForPublication(String environment) {
        delegate.lockEnvironmentForPublication(environment);
    }

    @Override
    public CatalogSnapshot loadCurrentSnapshot(String environment) {
        Objects.requireNonNull(environment, "environment must not be null");

        // L1：本地 Caffeine
        CatalogSnapshot cached = localCache.getIfPresent(environment);
        if (cached != null) {
            if (isValidCachedSnapshot(cached, environment)) {
                return cached;
            }
            localCache.invalidate(environment);
            log.warn("Invalid catalog snapshot found in local cache, loading from Redis/PostgreSQL: {}",
                    environment);
        }

        // L2：Redis
        try {
            RBucket<String> bucket = redissonClient.getBucket(RedisKeys.snapshotLatest(environment));
            String json = bucket.get();
            if (json != null) {
                CatalogSnapshot snapshot = deserialize(json);
                if (isValidCachedSnapshot(snapshot, environment)) {
                    localCache.put(environment, snapshot);
                    return snapshot;
                }
                bucket.delete();
                log.warn("Invalid catalog snapshot found in Redis, loading from PostgreSQL: {}",
                        environment);
            }
        } catch (Exception e) {
            log.warn("Redis snapshot cache read failed, falling back to PostgreSQL: {}",
                    e.getMessage());
        }

        // 事实来源：PostgreSQL，随后回填 L2 + L1
        CatalogSnapshot snapshot = delegate.loadCurrentSnapshot(environment);
        if (snapshot != null && snapshot.snapshotVersion() > 0) {
            try {
                RBucket<String> bucket =
                        redissonClient.getBucket(RedisKeys.snapshotLatest(environment));
                bucket.set(serialize(snapshot));
                localCache.put(environment, snapshot);
            } catch (Exception e) {
                log.warn("Redis snapshot cache back-fill failed: {}", e.getMessage());
            }
        }
        return snapshot;
    }

    private boolean isValidCachedSnapshot(CatalogSnapshot snapshot, String environment) {
        return snapshot != null
                && snapshot.snapshotVersion() > 0
                && environment.equals(snapshot.environment())
                && snapshot.digest() != null
                && !snapshot.digest().isBlank()
                && snapshot.digest().equals(CatalogSnapshotDigest.sha256(snapshot));
    }

    @Override
    public CatalogSnapshot loadSnapshot(long snapshotVersion) {
        // 历史快照不缓存，直接委派给 PostgreSQL
        return delegate.loadSnapshot(snapshotVersion);
    }

    @Override
    public Optional<CapabilityManifest> findCapability(String capabilityId, String version) {
        return delegate.findCapability(capabilityId, version);
    }

    @Override
    public List<SnapshotSummary> listSnapshots(String environment, int limit) {
        // 历史快照摘要不缓存，直接委派给 PostgreSQL
        return delegate.listSnapshots(environment, limit);
    }

    @Override
    public void saveSnapshot(CatalogSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        // 在外部数据库事务提交之前不要写入 Redis。发布事件会安排提交后的缓存更新。
        delegate.saveSnapshot(snapshot);
        localCache.invalidate(snapshot.environment());
    }

    /**
     * 使给定环境的各级缓存全部失效。
     *
     * <p>当 pub/sub 通知表明缓存快照可能已过期时调用。</p>
     *
     * @param environment 待失效缓存所属的环境
     */
    public void invalidate(String environment) {
        localCache.invalidate(environment);
        try {
            redissonClient.getBucket(RedisKeys.snapshotLatest(environment)).delete();
        } catch (Exception e) {
            log.warn("Redis snapshot cache invalidate failed: {}", e.getMessage());
        }
    }

    @Override
    public long reserveSnapshotVersion() {
        return delegate.reserveSnapshotVersion();
    }

    @Override
    public void recordSnapshotPublication(CatalogSnapshot snapshot, String eventType) {
        delegate.recordSnapshotPublication(snapshot, eventType);
        Runnable cacheUpdate = () -> cachePublishedSnapshot(snapshot);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cacheUpdate.run();
                }
            });
        } else {
            cacheUpdate.run();
        }
    }

    private void cachePublishedSnapshot(CatalogSnapshot snapshot) {
        try {
            RBucket<String> bucket =
                    redissonClient.getBucket(RedisKeys.snapshotLatest(snapshot.environment()));
            bucket.set(serialize(snapshot));
            localCache.put(snapshot.environment(), snapshot);
        } catch (Exception e) {
            log.warn("Redis snapshot cache update after database commit failed: {}", e.getMessage());
        }
    }

    private String serialize(CatalogSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize catalog snapshot", e);
        }
    }

    private CatalogSnapshot deserialize(String json) {
        try {
            return objectMapper.readValue(json, CatalogSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize catalog snapshot", e);
        }
    }
}
