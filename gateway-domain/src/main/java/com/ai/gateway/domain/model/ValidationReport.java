package com.ai.gateway.domain.model;

import java.util.List;

/**
 * The result of validating a ProtocolBinding or Manifest against structural,
 * semantic, security, and compatibility rules.
 *
 * <p>Defines the adapter port's {@code validate} method as
 * returning a {@code ValidationReport}. defines the full
 * 10-step validation pipeline for Manifest import.</p>
 *
 * <p>A report is considered valid only if {@code errors} is empty.
 * Warnings are informational and do not block publication.</p>
 *
 * @param valid whether validation passed (no errors)
 * @param errors the list of validation errors; empty if valid
 * @param warnings the list of validation warnings; non-blocking
 * @since 0.1.0
 */
public record ValidationReport(
        boolean valid,
        List<String> errors,
        List<String> warnings
) {

    /**
     * Compact constructor performing defensive copying.
     *
     * @param valid whether validation passed
     * @param errors the error list
     * @param warnings the warning list
     */
    public ValidationReport {
        java.util.Objects.requireNonNull(errors, "errors must not be null");
        java.util.Objects.requireNonNull(warnings, "warnings must not be null");
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    /**
     * Returns a valid report with no errors and no warnings.
     *
     * @return a valid empty report
     */
    public static ValidationReport success() {
        return new ValidationReport(true, List.of(), List.of());
    }

    /**
     * Returns an invalid report with the given errors and no warnings.
     *
     * @param errors the validation errors
     * @return an invalid report
     */
    public static ValidationReport failure(List<String> errors) {
        return new ValidationReport(false, errors, List.of());
    }
}
