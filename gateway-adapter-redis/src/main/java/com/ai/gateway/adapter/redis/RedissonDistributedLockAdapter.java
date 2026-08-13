package com.ai.gateway.adapter.redis;

import com.ai.gateway.domain.port.DistributedLockPort;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Redisson {@code RLock} implementation of {@link DistributedLockPort}.
 *
 * <p>Wraps Redisson's reentrant distributed lock, which provides automatic
 * lease renewal (watchdog) when no explicit lease time is given. Lock names
 * are prefixed with {@code gateway:lock:} to keep them in the gateway
 * key-space.</p>
 *
 * <p>This is the milestone-M4 building block from the tech-selection doc §7,
 * reusing the shared {@link RedissonClient} introduced in milestone M2.</p>
 *
 * @see DistributedLockPort
 * @since 0.1.0
 */
public class RedissonDistributedLockAdapter implements DistributedLockPort {

    private static final Logger log = LoggerFactory.getLogger(RedissonDistributedLockAdapter.class);

    private static final String LOCK_PREFIX = "gateway:lock:";

    private final RedissonClient redissonClient;

    /**
     * Constructs a new adapter.
     *
     * @param redissonClient the Redisson client; never {@code null}
     * @throws NullPointerException if {@code redissonClient} is null
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
