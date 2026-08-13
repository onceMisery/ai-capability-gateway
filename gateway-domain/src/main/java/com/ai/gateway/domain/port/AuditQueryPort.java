package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.AuditQueryCriteria;

import java.util.List;

/**
 * Port for querying audit events from the read-side audit store.
 *
 * <p>Separates the read-side query concern from the write-side
 * {@link AuditPort}. Adapters may query the same PostgreSQL table
 * or a dedicated read replica.</p>
 *
 * <p>Supports filtering by event type, capability ID, request ID,
 * result code, and time range, with pagination.</p>
 *
 * @see AuditEvent
 * @see AuditQueryCriteria
 * @since 0.1.0
 */
public interface AuditQueryPort {

    /**
     * Queries audit events matching the given criteria.
     *
     * <p>Results are ordered by timestamp descending (newest first).
     * Pagination is applied via the criteria's page and size fields.</p>
     *
     * @param criteria the query criteria; never {@code null}
     * @return the matching audit events; never {@code null}
     */
    List<AuditEvent> query(AuditQueryCriteria criteria);

    /**
     * Counts audit events matching the given criteria.
     *
     * <p>Used for pagination total-count calculation.</p>
     *
     * @param criteria the query criteria; never {@code null}
     * @return the number of matching events
     */
    long count(AuditQueryCriteria criteria);
}
