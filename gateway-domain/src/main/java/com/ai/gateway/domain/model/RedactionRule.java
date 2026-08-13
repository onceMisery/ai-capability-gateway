package com.ai.gateway.domain.model;

/**
 * A declarative redaction rule applied to a projected output field.
 *
 * <p>Specifies that after projection, the gateway applies
 * field-level redactions before the public output Schema validation
 *. Redactions are deterministic and do not
 * involve scripts or arbitrary expressions.</p>
 *
 * <p>The Contract Validator must verify that:</p>
 * <ul>
 * <li>The redaction path exists in the projected output.</li>
 * <li>The redacted result is consistent with the public Schema
 * (e.g., a {@code DELETE} rule must not remove a required field).</li>
 * </ul>
 *
 * @param path JSON Pointer to the field to redact (e.g., {@code "/customerName"})
 * @param method the redaction method to apply
 * @since 0.1.0
 */
public record RedactionRule(String path, RedactionMethod method) {

    /**
     * Compact constructor performing null checks.
     *
     * @param path JSON Pointer to the field
     * @param method the redaction method
     */
    public RedactionRule {
        java.util.Objects.requireNonNull(path, "path must not be null");
        java.util.Objects.requireNonNull(method, "method must not be null");
    }
}
