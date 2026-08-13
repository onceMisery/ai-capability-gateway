package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * A protocol-neutral invocation result returned by the {@code InvocationAdapter}
 *
 * <p>The neutral result contains only JSON-compatible data, a protocol status,
 * a stable error code, an error message, and call metadata. It does not
 * contain raw protocol objects, stack traces, internal addresses, interface
 * class names, or sensitive parameters.</p>
 *
 * <p>Defines the result processing order:</p>
 * <ol>
 * <li>The adapter converts the protocol result to a JSON-compatible tree.</li>
 * <li>The response size, depth, collection length, and processing time
 * are checked.</li>
 * <li>Envelope rules determine business success and extract data.</li>
 * <li>Projection whitelist constructs the public result.</li>
 * <li>Field redactions are applied.</li>
 * <li>Public output Schema is validated.</li>
 * <li>A structured result is generated.</li>
 * <li>An optional natural-language summary may be generated from the
 * redacted structured result.</li>
 * </ol>
 *
 * <p>The structured result is the authoritative result. If the natural-language
 * summary fails, the structured result must still be returned.</p>
 *
 * @param jsonData the JSON-compatible result data; null on error
 * @param protocolStatus the protocol-level status string (e.g., "OK", "TIMEOUT")
 * @param errorCode the stable error code; null on success
 * @param errorMessage the controlled error message; null on success
 * @param metadata call metadata (e.g., duration, provider node)
 * @since 0.1.0
 */
public record InvocationResult(
        Object jsonData,
        String protocolStatus,
        ErrorCode errorCode,
        String errorMessage,
        Map<String, String> metadata
) {

    /**
     * Compact constructor performing defensive copying.
     *
     * @param jsonData the result data
     * @param protocolStatus the protocol status
     * @param errorCode the error code
     * @param errorMessage the error message
     * @param metadata the metadata map
     */
    public InvocationResult {
        java.util.Objects.requireNonNull(protocolStatus, "protocolStatus must not be null");
        if (metadata != null) {
            metadata = Map.copyOf(metadata);
        }
    }
}
