package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.ConverterType;

/**
 * Port for the controlled type converter registry.
 *
 * <p>(Controlled Type Converters) defines a closed
 * enumeration of deterministic, single-value type converters. These
 * address the common mismatch between original value types (from model
 * output, Principal claims, or constants) and protocol parameter types
 * (e.g., date string to epoch-millis Long, or enum case normalization).</p>
 *
 * <p>Key constraints:</p>
 * <ul>
 * <li>Converters are implemented by deterministic Java code only — no
 * SpEL, script engines, or arbitrary expressions.</li>
 * <li>Each converter acts on a single source value to a single target
 * field; cross-field composition is not supported.</li>
 * <li>Conversion failures (format mismatch, overflow, illegal enum
 * value) are treated as {@code ARGUMENT_VALIDATION_FAILED}; no
 * silent fallback to the original value or null.</li>
 * <li>The Contract Validator must verify that the
 * converter name belongs to the registered whitelist at import
 * time. Unregistered converter names are rejected at the import
 * stage.</li>
 * <li>Adding or changing converters follows the platform release
 * process and is not dynamically extensible via Manifest.</li>
 * </ul>
 *
 * <p>Adapters implementing this port register the platform's built-in
 * converters. The port is a pure abstraction with no framework
 * dependencies.</p>
 *
 * @see ConverterType
 * @since 0.1.0
 */
public interface TypeConverterRegistry {

    /**
     * Converts a source value using the specified converter type.
     *
     * <p>: the converter acts on a single source value to a
     * single target field. Conversion failures (format mismatch, overflow,
     * illegal enum value) are treated as
     * {@code ARGUMENT_VALIDATION_FAILED}; no silent fallback to the
     * original value or null is permitted.</p>
     *
     * @param converterType the controlled converter type from the whitelist
     * @param sourceValue the original value to convert
     * @return the converted value
     * @throws IllegalArgumentException if the conversion fails (format
     * mismatch, overflow, or illegal enum value)
     */
    Object convert(ConverterType converterType, Object sourceValue);

    /**
     * Checks whether the given converter type is registered in the
     * platform's whitelist.
     *
     * <p> the Contract Validator must verify
     * that the converter name belongs to the registered whitelist at
     * import time. Unregistered converter names are rejected at the import
     * stage, not discovered after publication.</p>
     *
     * @param converterType the converter type to check
     * @return {@code true} if the converter is registered; {@code false} otherwise
     */
    boolean isRegistered(ConverterType converterType);
}
