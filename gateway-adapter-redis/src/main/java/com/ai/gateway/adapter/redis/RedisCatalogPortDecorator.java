package com.ai.gateway.adapter.redis;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.SnapshotSummary;
import com.ai.gateway.domain.port.CatalogPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Redis caching decorator for {@link CatalogPort}.
 *
 * <p>Implements the two-level cache described in the tech-selection doc §4:
 * a local Caffeine L1 (short TTL) in front of a Redis L2, with PostgreSQL as
 * the source of truth. Reads of the current snapshot consult L1, then L2,
 * then fall back to PostgreSQL and back-fill the caches. Writes are
 * Write-Through: the snapshot is persisted to PostgreSQL and then written to
 * Redis.</p>
 *
 * <p>Historical snapshot reads and capability lookups are delegated straight
 * to PostgreSQL — only the hot {@code loadCurrentSnapshot} path is cached.
 * On any Redis failure the decorator degrades gracefully to PostgreSQL so a
 * cache outage never breaks the gateway.</p>
 *
 * @see CatalogPort
 * @since 0.1.0
 */
public class RedisCatalogPortDecorator implements CatalogPort {

    private static final Logger log = LoggerFactory.getLogger(RedisCatalogPortDecorator.class);

    private final CatalogPort delegate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, CatalogSnapshot> localCache;

    /**
     * Constructs a new decorator.
     *
     * @param delegate the underlying (PostgreSQL) catalog port
     * @param redissonClient the Redisson client for the L2 cache
     * @param objectMapper the mapper used to serialize snapshots to JSON
     * @param localTtlSeconds the Caffeine L1 time-to-live in seconds
     * @throws NullPointerException if any argument is null
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
    public CatalogSnapshot loadCurrentSnapshot(String environment) {
        Objects.requireNonNull(environment, "environment must not be null");

        // L1: local Caffeine
        CatalogSnapshot cached = localCache.getIfPresent(environment);
        if (cached != null) {
            return cached;
        }

        // L2: Redis
        try {
            RBucket<String> bucket = redissonClient.getBucket(RedisKeys.snapshotLatest(environment));
            String json = bucket.get();
            if (json != null) {
                CatalogSnapshot snapshot = deserialize(json);
                localCache.put(environment, snapshot);
                return snapshot;
            }
        } catch (Exception e) {
            log.warn("Redis snapshot cache read failed, falling back to PostgreSQL: {}",
                    e.getMessage());
        }

        // Source of truth: PostgreSQL, then back-fill L2 + L1
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

    @Override
    public CatalogSnapshot loadSnapshot(long snapshotVersion) {
        // Historical snapshots are not cached; delegate to PostgreSQL.
        return delegate.loadSnapshot(snapshotVersion);
    }

    @Override
    public Optional<CapabilityManifest> findCapability(String capabilityId, String version) {
        return delegate.findCapability(capabilityId, version);
    }

    @Override
    public List<SnapshotSummary> listSnapshots(String environment, int limit) {
        // Historical snapshot summaries are not cached; delegate to PostgreSQL.
        return delegate.listSnapshots(environment, limit);
    }

    @Override
    public void saveSnapshot(CatalogSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        // Write-Through: persist to PostgreSQL first, then update Redis.
        delegate.saveSnapshot(snapshot);
        try {
            RBucket<String> bucket =
                    redissonClient.getBucket(RedisKeys.snapshotLatest(snapshot.environment()));
            bucket.set(serialize(snapshot));
            localCache.put(snapshot.environment(), snapshot);
        } catch (Exception e) {
            log.warn("Redis snapshot cache write failed (PostgreSQL already updated): {}",
                    e.getMessage());
        }
    }

    /**
     * Invalidates both cache levels for the given environment.
     *
     * <p>Invoked when a pub/sub notification signals that the cached snapshot
     * may be stale.</p>
     *
     * @param environment the environment whose cached snapshot is invalidated
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
