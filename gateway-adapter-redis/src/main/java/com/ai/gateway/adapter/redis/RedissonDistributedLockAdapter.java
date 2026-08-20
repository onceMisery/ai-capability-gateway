package com.ai.gateway.adapter.redis;

import com.ai.gateway.domain.port.DistributedLockPort;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@link DistributedLockPort} 基于 Redisson {@code RLock} 的实现。
 *
 * <p>封装 Redisson 的可重入分布式锁，未指定显式租约时间时提供自动续租（watchdog）。锁名统一
 * 以 {@code gateway:lock:} 为前缀，归入网关键空间。</p>
 *
 *
 * @author cmiracle@163.com
 * @see DistributedLockPort
 * @since 0.1.0
 */
@Slf4j
public class RedissonDistributedLockAdapter implements DistributedLockPort {

    private static final String LOCK_PREFIX = "gateway:lock:";

    private final RedissonClient redissonClient;

    /**
     * 构造新的适配器。
     *
     * @param redissonClient Redisson 客户端，不可为 {@code null}
     * @throws NullPointerException 当 {@code redissonClient} 为 {@code null} 时抛出
     */
    public RedissonDistributedLockAdapter(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient,
                "redissonClient must not be null");
    }

    @Override
    public boolean tryLock(String lockKey, long waitTimeMillis, long leaseTimeMillis)
            throws InterruptedException {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        RLock lock = redissonClient.getLock(LOCK_PREFIX + lockKey);
        boolean acquired = lock.tryLock(waitTimeMillis, leaseTimeMillis, TimeUnit.MILLISECONDS);
        if (log.isDebugEnabled()) {
            log.debug("tryLock: key={}, acquired={}", lockKey, acquired);
        }
        return acquired;
    }

    @Override
    public void unlock(String lockKey) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        RLock lock = redissonClient.getLock(LOCK_PREFIX + lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
