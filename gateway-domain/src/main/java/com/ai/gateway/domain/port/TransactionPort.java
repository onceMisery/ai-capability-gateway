package com.ai.gateway.domain.port;

/**
 * Port abstracting the transaction boundary for multi-repository use cases.
 *
 * <p>The application layer is framework-free and must not depend on Spring
 * {@code @Transactional}. This port lets application use cases demarcate a
 * single unit of work across multiple ports (e.g., save a catalog snapshot
 * and update manifest lifecycles atomically).</p>
 *
 * <p>Adapters implement this port with the infrastructure's transaction
 * manager (e.g., a Spring {@code TransactionTemplate}). Implementations must
 * guarantee that {@link TransactionWork} either commits entirely or rolls
 * back entirely, propagating any exception thrown by the work.</p>
 *
 * @since 0.1.0
 */
public interface TransactionPort {

    /**
     * Executes the given work inside a single transaction boundary.
     *
     * @param work the unit of work to run transactionally; never {@code null}
     * @param <T>  the return type of the work
     * @return the work's result; {@code null} if the work returned {@code null}
     * @throws RuntimeException rethrown from the work after the transaction
     *                          has been rolled back
     */
    <T> T inTransaction(TransactionWork<T> work);

    /**
     * A unit of work executed within one transaction.
     *
     * @param <T> the return type of the work
     */
    @FunctionalInterface
    interface TransactionWork<T> {
        T execute();
    }
}
