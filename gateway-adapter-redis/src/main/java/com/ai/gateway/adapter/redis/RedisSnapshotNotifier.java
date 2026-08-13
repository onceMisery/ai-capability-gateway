package com.ai.gateway.adapter.redis;

import com.ai.gateway.domain.port.SnapshotNotifier;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Redis Pub/Sub implementation of {@link SnapshotNotifier}.
 *
 * <p>Publishes snapshot lifecycle events onto Redis topics so that every
 * gateway instance receives them within seconds and can hot-reload its
 * in-memory snapshot (see {@link SnapshotCacheListener}). This replaces the
 * initial-release logging stub and is the notification bus described in the
 * tech-selection doc §4.</p>
 *
 * <p>Publication is fire-and-forget: the snapshot itself is persisted to
 * PostgreSQL (source of truth) and cached in Redis by the
 * {@code RedisCatalogPortDecorator}; the notification only carries the
 * version so subscribers know to reload.</p>
 *
 * @see RedisKeys
 * @since 0.1.0
 */
public class RedisSnapshotNotifier implements SnapshotNotifier {

    private static final Logger log = LoggerFactory.getLogger(RedisSnapshotNotifier.class);

    private final RedissonClient redissonClient;

    /**
     * Constructs a new notifier.
     *
     * @param redissonClient the Redisson client; never {@code null}
     * @throws NullPointerException if {@code redissonClient} is null
     */
    public RedisSnapshotNotifier(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient,
                "redissonClient must not be null");
    }

    @Override
    public void notifySnapshotPublished(long snapshotVersion) {
        RTopic topic = redissonClient.getTopic(RedisKeys.CHANNEL_SNAPSHOT_PUBLISHED);
        long receivers = topic.publish(String.valueOf(snapshotVersion));
        log.info("Snapshot published notification sent: version={}, receivers={}",
                snapshotVersion, receivers);
    }

    @Override
    public void notifySnapshotSuspended(long snapshotVersion) {
        RTopic topic = redissonClient.getTopic(RedisKeys.CHANNEL_SNAPSHOT_SUSPENDED);
        long receivers = topic.publish(String.valueOf(snapshotVersion));
        log.warn("Snapshot suspended notification sent: version={}, receivers={}",
                snapshotVersion, receivers);
    }
}
