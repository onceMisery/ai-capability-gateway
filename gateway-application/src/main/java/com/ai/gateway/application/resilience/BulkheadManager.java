package com.ai.gateway.application.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Per-Provider/Capability bulkhead isolation manager.
 *
 * <p>Bulkhead isolation limits the number of concurrent requests to a
 * single Provider or Capability, preventing a slow or failing downstream
 * service from exhausting the gateway's thread pool. Each
 * Provider/Capability key has its own independent {@link Semaphore} that
 * enforces the concurrency limit.</p>
 *
 * <p>When the concurrency limit is reached, new requests are rejected
 * immediately (or after a short timeout) rather than queuing indefinitely
 *. This Fail Fast behavior prevents resource exhaustion
 * and protects other capabilities from being affected.</p>
 *
 * <p>The default max concurrent per key is {@value DEFAULT_MAX_CONCURRENT}.
 * Individual keys can be configured with different limits using
 * {@link #configure(String, int)}.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: semaphores are stored in a {@link ConcurrentHashMap}
 * and created atomically via {@code computeIfAbsent}.</p>
 *
 * @see CircuitBreakerManager
 * @see RateLimiterManager
 * @since 0.1.0
 */
public final class BulkheadManager {

    private static final Logger log = LoggerFactory.getLogger(BulkheadManager.class);

    /** Default maximum concurrent requests per Provider/Capability key. */
    public static final int DEFAULT_MAX_CONCURRENT = 10;

    private final ConcurrentHashMap<String, Semaphore> bulkheads = new ConcurrentHashMap<>();
    private final int defaultMaxConcurrent;

    /**
     * Constructs a new BulkheadManager with the default max concurrent
     * of {@value DEFAULT_MAX_CONCURRENT} per key.
     */
    public BulkheadManager() {
        this(DEFAULT_MAX_CONCURRENT);
    }

    /**
     * Constructs a new BulkheadManager with a custom default max concurrent.
     *
     * @param defaultMaxConcurrent the default maximum concurrent requests per key
     */
    public BulkheadManager(int defaultMaxConcurrent) {
        if (defaultMaxConcurrent <= 0) {
            throw new IllegalArgumentException("defaultMaxConcurrent must be positive");
        }
        this.defaultMaxConcurrent = defaultMaxConcurrent;
    }

    /**
     * Configures the max concurrent for a specific Provider/Capability key.
     *
     * <p>If a semaphore already exists for the key, this method replaces it
     * with a new semaphore. Permits held by the old semaphore are lost.</p>
     *
     * @param key the Provider/Capability key
     * @param maxConcurrent the maximum concurrent requests
     * @throws NullPointerException if {@code key} is null
     * @throws IllegalArgumentException if {@code maxConcurrent} is not positive
     */
    public void configure(String key, int maxConcurrent) {
        Objects.requireNonNull(key, "key must not be null");
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        bulkheads.put(key, new Semaphore(maxConcurrent));
        log.debug("Configured bulkhead: key={}, maxConcurrent={}", key, maxConcurrent);
    }

    /**
     * Attempts to acquire a bulkhead permit for the given Provider/Capability
     * key within the specified timeout.
     *
     * <p>If the concurrency limit is reached, this method waits up to
     * {@code timeoutMs} milliseconds for a permit to become available. If
     * no permit is available within the timeout, a non-acquired lease is
     * returned and the caller must return a busy error to the client.</p>
     *
     * @param key the Provider/Capability key
     * @param timeoutMs the maximum time to wait for a permit in milliseconds
     * @return a {@link BulkheadLease} indicating whether the permit was acquired
     * @throws NullPointerException if {@code key} is null
     */
    public BulkheadLease acquire(String key, long timeoutMs) {
        Objects.requireNonNull(key, "key must not be null");
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must not be negative");
        }

        Semaphore semaphore = bulkheads.computeIfAbsent(key,
                k -> new Semaphore(defaultMaxConcurrent));

        try {
            boolean acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (acquired) {
                log.debug("Bulkhead permit acquired: key={}", key);
            } else {
                log.warn("Bulkhead permit denied (timeout): key={}, timeoutMs={}", key, timeoutMs);
            }
            return new BulkheadLease(key, acquired);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Bulkhead acquire interrupted: key={}", key);
            return new BulkheadLease(key, false);
        }
    }

    /**
     * Releases a previously acquired bulkhead permit.
     *
     * <p>If the lease was not acquired (i.e., {@code lease.acquired()} is
     * {@code false}), this method is a no-op.</p>
     *
     * @param lease the lease to release
     * @throws NullPointerException if {@code lease} is null
     */
    public void release(BulkheadLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        if (!lease.acquired()) {
            return;
        }
        Semaphore semaphore = bulkheads.get(lease.key());
        if (semaphore != null) {
            semaphore.release();
            log.debug("Bulkhead permit released: key={}", lease.key());
        } else {
            log.warn("Bulkhead semaphore not found on release: key={}", lease.key());
        }
    }

    /**
     * Returns the number of available permits for the given key.
     *
     * @param key the Provider/Capability key
     * @return the number of available permits, or -1 if the key is not configured
     * @throws NullPointerException if {@code key} is null
     */
    public int availablePermits(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Semaphore semaphore = bulkheads.get(key);
        return semaphore != null ? semaphore.availablePermits() : -1;
    }

    /**
     * A bulkhead lease representing the result of a permit acquisition.
     *
     * <p>If {@code acquired} is {@code true}, the caller holds a concurrency
     * permit and must call {@link #release(BulkheadLease)} when done. If
     * {@code false}, the permit was not acquired and the caller should
     * return a busy error.</p>
     *
     * @param key the Provider/Capability key
     * @param acquired whether the permit was successfully acquired
     */
    public record BulkheadLease(String key, boolean acquired) {
    }
}
