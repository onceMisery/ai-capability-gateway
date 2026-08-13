package com.ai.gateway.domain.model;

/**
 * Field-level redaction methods applied after projection but before
 * the public output Schema validation.
 *
 * <p>Redaction rules are declarative and deterministic; they do not
 * involve scripts or arbitrary expressions.</p>
 *
 * @see RedactionRule
 * @see OutputContract
 * @since 0.1.0
 */
public enum RedactionMethod {
    /**
     * Partially mask the field value (e.g., keep only the first and last
     * characters). The exact masking algorithm is deterministic and
     * implemented by the gateway.
     */
    PARTIAL_MASK,

    /**
     * Replace the field value with a one-way hash. The hash algorithm
     * is deterministic and configured by the platform, not by the Manifest.
     */
    HASH,

    /**
     * Remove the field entirely from the output. The field will not
     * appear in the projected result.
     */
    DELETE
}
