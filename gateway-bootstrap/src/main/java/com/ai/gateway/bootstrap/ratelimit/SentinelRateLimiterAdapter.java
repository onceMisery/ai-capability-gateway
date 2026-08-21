package com.ai.gateway.bootstrap.ratelimit;

import com.ai.gateway.domain.port.RateLimiterPort;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * 基于 Sentinel 的 {@link RateLimiterPort} 实现。
 *
 * <p>将每个 {@code (dimension, key)} 对映射为名为
 * {@code gateway:{dimension}:{key}} 的 Sentinel 资源，并通过编程式
 * {@link SphU} API 尝试非阻塞地获取许可。当流控或降级规则阻断该资源时，
 * {@link #tryAcquire} 立即返回 {@code false}（快速失败），满足规范要求的
 * “网关应返回清晰的限流/繁忙错误，而非无限排队”。</p>
 *
 * <p>规则由 {@link SentinelRuleInitializer} 加载。本适配器仅使用
 * {@code sentinel-core}，不依赖 Sentinel Dashboard 或 Spring Cloud Alibaba，
 * 符合技术选型文档 §5。</p>
 *
 * @see SentinelRuleInitializer
 * @since 0.1.0
 * @author cmiracle@163.com
 */
public class SentinelRateLimiterAdapter implements RateLimiterPort {

    private static final String RESOURCE_PREFIX = "gateway:";

    @Override
    public boolean tryAcquire(String dimension, String key, int permits) {
        String resource = RESOURCE_PREFIX + dimension + ":" + key;
        Entry entry = null;
        try {
            entry = SphU.entry(resource, EntryType.IN, permits);
            return true;
        } catch (BlockException e) {
            // 被限流或熔断：快速失败。
            return false;
        } finally {
            if (entry != null) {
                entry.exit(permits);
            }
        }
    }
}
