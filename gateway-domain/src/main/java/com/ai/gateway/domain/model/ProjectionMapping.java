package com.ai.gateway.domain.model;

/**
 * A single JSON Pointer projection mapping from Provider data to the
 * public output.
 *
 * <p>Defines {@code projection} as a whitelist of JSON Pointer
 * mappings from the Provider's data payload to the public output. Fields
 * not mapped by any projection entry do not leave the gateway.</p>
 *
 * <p>If no projection is configured, the entire extracted data must match
 * the {@code publicSchema} exactly.</p>
 *
 * @param from JSON Pointer into the Provider data payload (e.g., {@code "/orderNo"})
 * @param to JSON Pointer into the public output (e.g., {@code "/orderNo"})
 * @since 0.1.0
 */
public record ProjectionMapping(String from, String to) {

    /**
     * Compact constructor performing null checks.
     *
     * @param from JSON Pointer into Provider data
     * @param to JSON Pointer into public output
     */
    public ProjectionMapping {
        java.util.Objects.requireNonNull(from, "from must not be null");
        java.util.Objects.requireNonNull(to, "to must not be null");
    }
}
