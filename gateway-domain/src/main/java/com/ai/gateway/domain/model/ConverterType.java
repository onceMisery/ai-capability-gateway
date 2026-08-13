package com.ai.gateway.domain.model;

/**
 * Closed whitelist of controlled type converters for argument binding.
 *
 * <p>Defines a closed enumeration of deterministic, single-value
 * type converters. These address the common mismatch between the original
 * value types (from model output, Principal claims, or constants) and the
 * protocol parameter types (e.g., date string to epoch-millis Long, or
 * enum case normalization).</p>
 *
 * <p>Key constraints:</p>
 * <ul>
 * <li>Converters are implemented by deterministic Java code only — no
 * SpEL, script engines, or arbitrary expressions.</li>
 * <li>Each converter acts on a single source value to a single target
 * field; cross-field composition is not supported.</li>
 * <li>Conversion failures (format mismatch, overflow, illegal enum value)
 * are treated as {@code ARGUMENT_VALIDATION_FAILED}; no silent
 * fallback to the original value or null.</li>
 * <li>The Contract Validator must verify that the converter
 * name belongs to the registered whitelist at import time.</li>
 * <li>Adding or changing converters follows the platform release process
 * and is not dynamically extensible via Manifest.</li>
 * </ul>
 *
 * @see ArgumentBinding
 * @see FieldBinding
 * @since 0.1.0
 */
public enum ConverterType {
    /**
     * Converts an ISO-8601 date/time string to epoch milliseconds (Long).
     * For example, {@code "2026-07-21T10:00:00Z"} becomes
     * {@code 1753092000000L}.
     */
    ISO_DATE_TO_EPOCH_MILLIS,

    /**
     * Normalizes an enum-like string to uppercase. For example,
     * {@code "pending"} becomes {@code "PENDING"}.
     */
    ENUM_UPPERCASE,

    /**
     * Trims leading and trailing whitespace from a string value.
     * For example, {@code " order123 "} becomes {@code "order123"}.
     */
    STRING_TRIM
}
