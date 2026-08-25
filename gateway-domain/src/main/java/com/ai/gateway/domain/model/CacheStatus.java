package com.ai.gateway.domain.model;

/**
 * 缓存子系统状态的只读视图。
 *
 * <p>通过管理控制台暴露，展示当前缓存提供方、配置与快照缓存状态。</p>
 *
 * @param provider 缓存提供方（stub 或 redis）
 * @param redisAddress Redis 地址（生产环境掩码）
 * @param localTtlSeconds L1（Caffeine）本地缓存 TTL（秒）
 * @param currentSnapshotVersion 当前已加载的快照版本
 * @param lastRefreshTimestamp 上次快照刷新时间戳（epoch 毫秒）
 * @since 0.1.0
 */
public record CacheStatus(
        String provider,
        String redisAddress,
        int localTtlSeconds,
        long currentSnapshotVersion,
        long lastRefreshTimestamp
) {

    /**
     * 紧凑构造器，执行 null 检查。
     */
    public CacheStatus {
        java.util.Objects.requireNonNull(provider, "provider must not be null");
        java.util.Objects.requireNonNull(redisAddress, "redisAddress must not be null");
    }
}
