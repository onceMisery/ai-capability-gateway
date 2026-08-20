package com.ai.gateway.adapter.redis;

/**
 * 网关基础设施组件的 Redis 键空间约定。
 *
 * <p>集中管理键与频道名称，使快照缓存、pub/sub 通知频道以及（后续）分布式锁共享一致的
 * 命名空间 。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class RedisKeys {

    /**
     * Prefix for all gateway keys.
     */
    public static final String PREFIX = "gateway:";

    /**
     * Pub/Sub channel carrying published snapshot versions.
     */
    public static final String CHANNEL_SNAPSHOT_PUBLISHED = "gateway:channel:snapshot-published";

    /**
     * Pub/Sub channel carrying suspended snapshot versions.
     */
    public static final String CHANNEL_SNAPSHOT_SUSPENDED = "gateway:channel:snapshot-suspended";

    private RedisKeys() {
    }

    /**
     * Builds the Redis key holding the JSON-serialized latest snapshot for an
     * environment.
     *
     * @param environment the target environment
     * @return the key {@code gateway:snapshot:{environment}:latest}
     */
    public static String snapshotLatest(String environment) {
        return "gateway:snapshot:" + environment + ":latest";
    }

    /**
     * Builds the Redis key holding the current snapshot version for an
     * environment.
     *
     * @param environment the target environment
     * @return the key {@code gateway:snapshot:{environment}:version}
     */
    public static String snapshotVersion(String environment) {
        return "gateway:snapshot:" + environment + ":version";
    }
}
