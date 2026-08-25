package com.ai.gateway.domain.port;

import java.util.function.Supplier;

/**
 * 跨网关实例的分布式互斥端口。
 *
 * <p>用于保证不得在不同节点上并发执行的操作的原子性，例如：</p>
 * <ul>
 * <li>快照发布（防止并发发布产生冲突版本）。</li>
 * <li>Outbox 中继轮询（防止跨实例重复消费）。</li>
 * <li>管理操作（同一能力不得被并发审批）。</li>
 * </ul>
 *
 * <p>实现此端口的适配器提供可重入、自动续期的分布式锁（如带看门狗的 Redisson
 * {@code RLock}）。该端口是纯粹的领域抽象，不依赖任何框架。</p>
 *
 * @since 0.1.0
 */
public interface DistributedLockPort {

    /**
     * 尝试获取具名锁，最多等待指定时长。
     *
     * <p>该锁可重入。若 {@code leaseTimeMillis} 为负，实现可持有锁直到显式释放（在支持
     * 的前提下自动续期）。</p>
     *
     * @param lockKey 逻辑锁名
     * @param waitTimeMillis 等待锁的最大时长
     * @param leaseTimeMillis 持有锁的最大时长；负值为自动续期 / 显式释放
     * @return 若获取成功则为 {@code true}；若先超时则为 {@code false}
     * @throws InterruptedException 当等待线程被中断时
     */
    boolean tryLock(String lockKey, long waitTimeMillis, long leaseTimeMillis)
            throws InterruptedException;

    /**
     * 若当前线程持有具名锁，则释放它。
     *
     * <p>释放未持有的锁为无操作。</p>
     *
     * @param lockKey 逻辑锁名
     */
    void unlock(String lockKey);

    /**
     * 在持有具名锁期间执行动作，结束后无论结果如何都释放锁。
     *
     * @param lockKey 逻辑锁名
     * @param waitTimeMillis 等待锁的最大时长
     * @param leaseTimeMillis 持有锁的最大时长；负值为自动续期 / 显式释放
     * @param action 在锁保护下运行的动作
     * @param <T> 动作结果类型
     * @return 动作结果
     * @throws LockAcquisitionException 当无法获取锁时
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
     * 当无法获取分布式锁时抛出。
     */
    class LockAcquisitionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * 构造一个新的异常。
         *
         * @param message 详细消息
         */
        public LockAcquisitionException(String message) {
            super(message);
        }

        /**
         * 构造一个带原因的新异常。
         *
         * @param message 详细消息
         * @param cause 底层原因
         */
        public LockAcquisitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
