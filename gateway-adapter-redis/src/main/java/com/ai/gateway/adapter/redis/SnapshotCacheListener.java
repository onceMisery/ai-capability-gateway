package com.ai.gateway.adapter.redis;

import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

/**
 * Subscribes to the Redis snapshot pub/sub channels and hot-reloads the
 * in-memory catalog on notification.
 *
 * <p>On a "snapshot published" message this listener re-runs the same
 * activation sequence used at startup: it asks the {@link InMemoryCatalogManager}
 * to load and activate the latest snapshot (via the injected
 * {@code CatalogPort}, which is the Redis decorator when caching is enabled)
 * and rebuilds the BM25 retrieval index. This delivers the multi-instance
 * eventual consistency described in the tech-selection doc §4: publish once,
 * every instance converges within seconds.</p>
 *
 * @see RedisSnapshotNotifier
 * @see RedisCatalogPortDecorator
 * @since 0.1.0
 */
public class SnapshotCacheListener implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCacheListener.class);

    private final RedissonClient redissonClient;
    private final InMemoryCatalogManager catalogManager;
    private final String environment;

    private volatile int publishedListenerId;
    private volatile int suspendedListenerId;

    /**
     * Constructs a new listener.
     *
     * @param redissonClient the Redisson client providing the topics
     * @param catalogManager the in-memory catalog manager (L1) to refresh
     * @param environment the environment this instance serves
     * @throws NullPointerException if any argument is null
     */
    public SnapshotCacheListener(RedissonClient redissonClient,
                                 InMemoryCatalogManager catalogManager,
                                 String environment) {
        this.redissonClient = Objects.requireNonNull(redissonClient,
                "redissonClient must not be null");
        this.catalogManager = Objects.requireNonNull(catalogManager,
                "catalogManager must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    /**
     * Subscribes to the snapshot lifecycle topics.
     */
    @Override
    public void afterPropertiesSet() {
        RTopic publishedTopic = redissonClient.getTopic(RedisKeys.CHANNEL_SNAPSHOT_PUBLISHED);
        publishedListenerId = publishedTopic.addListener(String.class,
                (MessageListener<String>) (channel, version) -> onPublished(version));

        RTopic suspendedTopic = redissonClient.getTopic(RedisKeys.CHANNEL_SNAPSHOT_SUSPENDED);
        suspendedListenerId = suspendedTopic.addListener(String.class,
                (MessageListener<String>) (channel, version) ->
                        log.warn("Snapshot suspended notification received: version={}", version));

        log.info("Snapshot cache listener subscribed: environment={}", environment);
    }

    /**
     * Removes the topic listeners on shutdown.
     */
    @Override
    public void destroy() {
        try {
            redissonClient.getTopic(RedisKeys.CHANNEL_SNAPSHOT_PUBLISHED)
                    .removeListener(publishedListenerId);
            redissonClient.getTopic(RedisKeys.CHANNEL_SNAPSHOT_SUSPENDED)
                    .removeListener(suspendedListenerId);
        } catch (Exception e) {
            log.debug("Snapshot cache listener unsubscribe skipped: {}", e.getMessage());
        }
    }

    private void onPublished(String version) {
        log.info("Snapshot published notification received: version={}, environment={}",
                version, environment);
        try {
            boolean scheduled = catalogManager.requestRefresh(environment);
            log.info("Snapshot hot-reload notification handled: scheduled={}, activeVersion={}",
                    scheduled, catalogManager.getCurrentSnapshotVersion());
        } catch (Exception e) {
            log.error("Snapshot hot-reload notification failed, retaining old snapshot: {}",
                    e.getMessage());
        }
    }
}
