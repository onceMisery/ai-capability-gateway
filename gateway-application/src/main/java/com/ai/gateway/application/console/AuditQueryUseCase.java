package com.ai.gateway.application.console;

import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.AuditQueryCriteria;
import com.ai.gateway.domain.port.AuditQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Use case for querying audit events from the admin console.
 *
 * <p>Delegates to the {@link AuditQueryPort} for read-side queries with
 * filtering, pagination, and time-range support.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable state.</p>
 *
 * @since 0.1.0
 */
public final class AuditQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryUseCase.class);

    private final AuditQueryPort auditQueryPort;

    /**
     * Constructs a new AuditQueryUseCase.
     *
     * @param auditQueryPort the audit query port
     */
    public AuditQueryUseCase(AuditQueryPort auditQueryPort) {
        this.auditQueryPort = Objects.requireNonNull(auditQueryPort);
    }

    /**
     * Queries audit events with filtering and pagination.
     *
     * @param eventType optional event type filter
     * @param capabilityId optional capability identifier filter
     * @param requestId optional request identifier filter
     * @param resultCode optional result code filter
     * @param from optional start of time range (epoch millis)
     * @param to optional end of time range (epoch millis)
     * @param page the 1-based page number
     * @param size the page size
     * @return a map containing "items" (list of AuditEvent) and "total" (count)
     */
    public Map<String, Object> queryAuditEvents(String eventType, String capabilityId,
                                                  String requestId, String resultCode,
                                                  Long from, Long to,
                                                  int page, int size) {
        Instant fromInstant = from != null ? Instant.ofEpochMilli(from) : null;
        Instant toInstant = to != null ? Instant.ofEpochMilli(to) : null;

        AuditQueryCriteria criteria = new AuditQueryCriteria(
                eventType, capabilityId, requestId, resultCode,
                fromInstant, toInstant, page, size
        );

        List<AuditEvent> events = auditQueryPort.query(criteria);
        long total = auditQueryPort.count(criteria);

        return Map.of(
                "items", events,
                "total", total,
                "page", page,
                "size", size
        );
    }
}
