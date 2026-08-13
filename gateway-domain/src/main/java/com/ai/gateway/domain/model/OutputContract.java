package com.ai.gateway.domain.model;

import java.util.List;
import java.util.Map;

/**
 * The complete output contract for a capability, defining how the protocol
 * response is unwrapped, projected, redacted, and validated before being
 * returned to the caller.
 *
 * <p>Defines the response processing pipeline:</p>
 * <ol>
 * <li>The adapter converts the protocol result to a JSON-compatible tree.</li>
 * <li>If {@code mode} is {@link OutputMode#ENVELOPE}, the envelope config
 * is used to determine business success and extract the data payload.</li>
 * <li>If {@code mode} is {@link OutputMode#DIRECT}, the root node is the data.</li>
 * <li>The {@code projections} whitelist maps Provider data to the public
 * output. Unmapped fields do not leave the gateway.</li>
 * <li>The {@code redactions} rules apply field-level masking, hashing, or
 * deletion.</li>
 * <li>The {@code publicSchema} validates the final public output.</li>
 * <li>The {@code maxBytes} limit enforces response size constraints.</li>
 * </ol>
 *
 * <p>Path-not-found, type mismatch, response-over-limit, or indeterminate
 * business success must be treated as protocol errors — the raw object must
 * never be returned to the user or model directly.</p>
 *
 * @param mode the output mode (envelope or direct)
 * @param envelope the envelope configuration; required for ENVELOPE mode,
 * ignored for DIRECT mode
 * @param projections the JSON Pointer projection whitelist; if empty, the
 * entire extracted data must match publicSchema
 * @param publicSchema the JSON Schema 2020-12 validating the public output
 * @param redactions the field-level redaction rules
 * @param maxBytes the maximum response size in bytes
 * @since 0.1.0
 */
public record OutputContract(
        OutputMode mode,
        EnvelopeConfig envelope,
        List<ProjectionMapping> projections,
        Map<String, Object> publicSchema,
        List<RedactionRule> redactions,
        int maxBytes
) {
    /**
     * Compact constructor performing defensive copying.
     *
     * @param mode the output mode
     * @param envelope the envelope configuration
     * @param projections the projection mappings
     * @param publicSchema the public JSON Schema
     * @param redactions the redaction rules
     * @param maxBytes the max response bytes
     */
    public OutputContract {
        java.util.Objects.requireNonNull(mode, "mode must not be null");
        java.util.Objects.requireNonNull(projections, "projections must not be null");
        java.util.Objects.requireNonNull(publicSchema, "publicSchema must not be null");
        java.util.Objects.requireNonNull(redactions, "redactions must not be null");
        projections = List.copyOf(projections);
        redactions = List.copyOf(redactions);
        publicSchema = Map.copyOf(publicSchema);
    }
}
