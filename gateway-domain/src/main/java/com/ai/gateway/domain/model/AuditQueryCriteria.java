package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * Criteria for querying audit events from the read-side audit port.
 *
 * <p>All fields are optional filters; null means no filter on that dimension.
 * Pagination is 1-based.</p>
 *
 * @param eventType optional event type filter
 * @param capabilityId optional capability identifier filter
 * @param requestId optional request identifier filter
 * @param resultCode optional result code filter
 * @param from optional start of time range (inclusive)
 * @param to optional end of time range (exclusive)
 * @param page the 1-based page number (default 1)
 * @param size the page size (default 20, max 100)
 * @since 0.1.0
 */
public record AuditQueryCriteria(
        String eventType,
        String capabilityId,
        String requestId,
        String resultCode,
        Instant from,
        Instant to,
        int page,
        int size
) {

    /**
     * Creates criteria with defaults for pagination.
     */
    public AuditQueryCriteria {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 100) size = 100;
    }

    /**
     * Creates criteria with default pagination (page=1, size=20).
     */
    public static AuditQueryCriteria of(String eventType, String capabilityId,
                                         String requestId, String resultCode,
                                         Instant from, Instant to) {
        return new AuditQueryCriteria(eventType, capabilityId, requestId, resultCode, from, to, 1, 20);
    }
}
