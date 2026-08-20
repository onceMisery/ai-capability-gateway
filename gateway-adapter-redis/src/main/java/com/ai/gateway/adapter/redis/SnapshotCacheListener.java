package com.ai.gateway.adapter.redis;

import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

/**
 * 订阅 Redis 快照 pub/sub 频道，并在收到通知时热加载内存目录。
 *
 * <p>收到“快照已发布”消息后，该监听器重新执行与启动相同的激活流程：请求
 * {@link InMemoryCatalogManager} 加载并激活最新快照（经由注入的 {@code CatalogPort}，
 * 启用缓存时即 Redis 装饰器），并重建 BM25 检索引擎。由此实现技术选型文档 §4 所述的多实例
 * 最终一致性：发布一次，各实例在数秒内收敛。</p>
 *
 * @author cmiracle@163.com
 * @see RedisSnapshotNotifier
 * @see RedisCatalogPortDecorator
 * @since 0.1.0
 */
@Slf4j
public class SnapshotCacheListener implements InitializingBean, DisposableBean {

    private final RedissonClient redissonClient;
    private final InMemoryCatalogManager catalogManager;
    private final String environment;

    private volatile int publishedListenerId;
    private volatile int suspendedListenerId;

    /**
     * 构造新的监听器。
     *
     * @param redissonClient 提供频道的 Redisson 客户端
     * @param catalogManager 待刷新的内存目录管理器（L1）
     * @param environment 本实例所服务的环境
     * @throws NullPointerException 任意参数为 {@code null} 时抛出
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
