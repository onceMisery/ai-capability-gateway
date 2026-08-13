package com.ai.gateway.bootstrap.ratelimit;

import com.ai.gateway.domain.port.RateLimiterPort;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * Sentinel-backed {@link RateLimiterPort} implementation.
 *
 * <p>Maps each {@code (dimension, key)} pair to a Sentinel resource named
 * {@code gateway:{dimension}:{key}} and attempts a non-blocking permit
 * acquisition via the programmatic {@link SphU} API. When a flow or
 * degrade rule blocks the resource, {@link #tryAcquire} returns
 * {@code false} immediately (Fail Fast), satisfying the spec's requirement
 * that the gateway return a clear rate-limit/busy error rather than queue
 * indefinitely.</p>
 *
 * <p>Rules are loaded by {@link SentinelRuleInitializer}. This adapter uses
 * {@code sentinel-core} only — no Sentinel Dashboard or Spring Cloud
 * Alibaba dependency, per the tech-selection doc §5.</p>
 *
 * @see SentinelRuleInitializer
 * @since 0.1.0
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
            // Limited or circuit-broken: fail fast.
            return false;
        } finally {
            if (entry != null) {
                entry.exit(permits);
            }
        }
    }
}
