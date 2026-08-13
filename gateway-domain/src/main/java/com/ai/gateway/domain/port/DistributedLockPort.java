package com.ai.gateway.domain.port;

import java.util.function.Supplier;

/**
 * Port for distributed mutual exclusion across gateway instances.
 *
 * <p>Used to guarantee atomicity of operations that must not run
 * concurrently on different nodes, e.g.:</p>
 * <ul>
 * <li>Snapshot publication (prevent concurrent publishes from producing
 * conflicting versions).</li>
 * <li>Outbox relay polling (prevent duplicate consumption across
 * instances).</li>
 * <li>Administrative operations (the same capability must not be approved
 * concurrently).</li>
 * </ul>
 *
 * <p>Adapters implementing this port provide a reentrant, auto-renewing
 * distributed lock (e.g., Redisson {@code RLock} with its watchdog). The
 * port is a pure abstraction with no framework dependencies.</p>
 *
 * @since 0.1.0
 */
public interface DistributedLockPort {

    /**
     * Attempts to acquire the named lock, waiting up to the given time.
     *
     * <p>The lock is reentrant. If {@code leaseTimeMillis} is negative, the
     * implementation may hold the lock until explicitly released (with
     * automatic renewal where supported).</p>
     *
     * @param lockKey the logical lock name
     * @param waitTimeMillis the maximum time to wait for the lock
     * @param leaseTimeMillis the maximum time to hold the lock; negative for
     * auto-renewal / explicit release
     * @return {@code true} if the lock was acquired; {@code false} if the
     * wait time elapsed first
     * @throws InterruptedException if the waiting thread is interrupted
     */
    boolean tryLock(String lockKey, long waitTimeMillis, long leaseTimeMillis)
            throws InterruptedException;

    /**
     * Releases the named lock if it is held by the current thread.
     *
     * <p>Releasing a lock that is not held is a no-op.</p>
     *
     * @param lockKey the logical lock name
     */
    void unlock(String lockKey);

    /**
     * Executes an action while holding the named lock, releasing it
     * afterwards regardless of outcome.
     *
     * @param lockKey the logical lock name
     * @param waitTimeMillis the maximum time to wait for the lock
     * @param leaseTimeMillis the maximum time to hold the lock; negative for
     * auto-renewal / explicit release
     * @param action the action to run under the lock
     * @param <T> the action result type
     * @return the action result
     * @throws LockAcquisitionException if the lock could not be acquired
     */
    default <T> T withLock(String lockKey, long waitTimeMillis, long leaseTimeMillis,
                           Supplier<T> action) {
        boolean acquired;
        try {
            acquired = tryLock(lockKey, waitTimeMillis, leaseTimeMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(
                    "Interrupted while acquiring lock: " + lockKey, e);
        }
        if (!acquired) {
            throw new LockAcquisitionException("Failed to acquire lock: " + lockKey);
        }
        try {
            return action.get();
        } finally {
            unlock(lockKey);
        }
    }

    /**
     * Thrown when a distributed lock cannot be acquired.
     */
    class LockAcquisitionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Constructs a new exception.
         *
         * @param message the detail message
         */
        public LockAcquisitionException(String message) {
            super(message);
        }

        /**
         * Constructs a new exception with a cause.
         *
         * @param message the detail message
         * @param cause the underlying cause
         */
        public LockAcquisitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
