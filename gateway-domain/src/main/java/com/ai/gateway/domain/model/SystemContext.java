package com.ai.gateway.domain.model;

import java.util.Set;
import java.util.Collections;

/**
 * Platform execution context values available to {@link ArgumentSource#SYSTEM}
 * parameter bindings.
 *
 * <p>Defines {@code SYSTEM} as a controlled argument source that
 * reads platform-built-in whitelisted paths only. Manifests cannot declare
 * new system variables, and neither users nor models may write to the
 * execution context. The whitelisted paths are:</p>
 *
 * <ul>
 * <li>{@code /traceId} - the distributed trace identifier</li>
 * <li>{@code /deadlineEpochMs} - the request's absolute deadline in epoch
 * milliseconds</li>
 * <li>{@code /idempotencyKey} - the server-generated idempotency key
 * for write operations; absent for read-only requests</li>
 * <li>{@code /locale} - the request locale for internationalization</li>
 * </ul>
 *
 * <p>If a capability references a non-existent system value, execution must
 * be rejected.</p>
 *
 * @param traceId the distributed trace identifier
 * @param deadlineEpochMs the absolute deadline in epoch milliseconds
 * @param idempotencyKey the server-generated idempotency key; may be null
 * for read-only requests
 * @param locale the request locale (e.g., "zh-CN")
 * @since 0.1.0
 */
public record SystemContext(
        String traceId,
        long deadlineEpochMs,
        String idempotencyKey,
        String locale
) {
    /**
     * The immutable set of allowed system context paths.
     */
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/traceId",
            "/deadlineEpochMs",
            "/idempotencyKey",
            "/locale"
    );

    /**
     * Compact constructor performing null checks on required fields.
     *
     * @param traceId the distributed trace identifier
     * @param deadlineEpochMs the absolute deadline in epoch milliseconds
     * @param idempotencyKey the server-generated idempotency key (may be null)
     * @param locale the request locale
     */
    public SystemContext {
        java.util.Objects.requireNonNull(traceId, "traceId must not be null");
        java.util.Objects.requireNonNull(locale, "locale must not be null");
    }

    /**
     * Returns an unmodifiable view of the allowed system context paths.
     *
     * @return the set of whitelisted paths that SYSTEM bindings may reference
     */
    public static Set<String> allowedPaths() {
        return Collections.unmodifiableSet(ALLOWED_PATHS);
    }
}
