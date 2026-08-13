package com.ai.gateway.application.operation;

import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.port.OperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Use case for querying the status of a write operation.
 *
 * <p>The status query allows callers to determine the current state of a
 * write operation in the two-phase Prepare/Confirm protocol. This is
 * particularly important for resolving {@code UNKNOWN} states, where the
 * request may have reached the Provider but the gateway did not receive a
 * definitive response.</p>
 *
 * <p>The gateway must not auto-re-execute the natural-language request for
 * {@code UNKNOWN} operations. Instead, a recovery task uses the idempotency
 * key to query the Provider status. Unresolvable cases enter
 * {@code MANUAL_REVIEW}.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @see OperationRepository
 * @since 0.1.0
 */
public final class OperationStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(OperationStatusUseCase.class);

    private final OperationRepository operationRepository;

    /**
     * Constructs a new OperationStatusUseCase with the required dependency.
     *
     * @param operationRepository the repository for querying operation records
     * @throws NullPointerException if {@code operationRepository} is null
     */
    public OperationStatusUseCase(OperationRepository operationRepository) {
        this.operationRepository = Objects.requireNonNull(operationRepository,
                "operationRepository must not be null");
    }

    /**
     * Queries the current status of a write operation.
     *
     * @param operationId the unique operation identifier
     * @return the operation record, or {@code null} if not found
     * @throws NullPointerException if {@code operationId} is null
     */
    public OperationRecord query(String operationId) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        log.debug("Querying operation status: operationId={}", operationId);

        Optional<OperationRecord> record = operationRepository.findById(operationId);
        if (record.isEmpty()) {
            log.warn("Operation not found: operationId={}", operationId);
            return null;
        }

        OperationRecord operation = record.get();
        log.debug("Operation status: operationId={}, state={}", operationId, operation.state());
        return operation;
    }
}
