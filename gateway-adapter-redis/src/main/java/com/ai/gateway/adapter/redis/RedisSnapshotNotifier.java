package com.ai.gateway.adapter.redis;

import com.ai.gateway.domain.port.SnapshotNotifier;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * {@link SnapshotNotifier} 基于 Redis Pub/Sub 的实现。
 *
 * <p>将快照生命周期事件发布到 Redis 主题，使每个网关实例在数秒内收到并热加载其内存快照
 * （见 {@link SnapshotCacheListener}）。它替代了初始版本的日志桩，是技术选型文档 §4 所述的通知总线。</p>
 *
 * <p>发布为即发即弃（fire-and-forget）：快照本身持久化到 PostgreSQL（事实来源）并由
 * {@code RedisCatalogPortDecorator} 缓存进 Redis；通知仅携带版本号，供订阅方据此重新加载。</p>
 *
 * @author cmiracle@163.com
 * @see RedisKeys
 * @since 0.1.0
 */
@Slf4j
public class RedisSnapshotNotifier implements SnapshotNotifier {

    private final RedissonClient redissonClient;

    /**
     * 构造新的通知器。
     *
     * @param redissonClient Redisson 客户端，不可为 {@code null}
     * @throws NullPointerException 当 {@code redissonClient} 为 {@code null} 时抛出
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
