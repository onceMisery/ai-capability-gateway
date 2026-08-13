package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.ValidationReport;

import java.util.Map;

/**
 * Port for JSON Schema 2020-12 validation.
 *
 * <p>(Input Schema) specifies that {@code spec.inputSchema}
 * uses JSON Schema 2020-12 and must obey the following rules:</p>
 * <ul>
 * <li>The root type must be {@code object}.</li>
 * <li>{@code additionalProperties: false} must be explicitly set.</li>
 * <li>Strings must define reasonable {@code maxLength}; arrays must
 * define {@code maxItems}.</li>
 * <li>Enumerations, amounts, timestamps, and IDs must use explicit
 * constraints.</li>
 * <li>The schema must not contain {@code orgId}, {@code tenantId},
 * {@code userId}, roles, service addresses, or interface names
 * (trusted context fields).</li>
 * <li>Sensitive business fields should use {@code x-sensitive: true}
 * for log and audit redaction.</li>
 * <li>Descriptions are for disambiguation and must not contain keys,
 * internal network information, or unverifiable instructions.</li>
 * </ul>
 *
 * <p>Only MODEL-source parameters or MODEL leaf fields within composite
 * parameters may appear in this schema. The validator is also used for
 * validating LLM output and the public output schema
 *.</p>
 *
 * <p>Adapters implementing this port wrap a JSON Schema 2020-12
 * validation library. The port is a pure abstraction with no framework
 * dependencies.</p>
 *
 * @see ValidationReport
 * @since 0.1.0
 */
public interface SchemaValidator {

    /**
     * Validates the given data against the provided JSON Schema.
     *
     * <p>: the schema must be a JSON Schema 2020-12 document
     * with {@code additionalProperties: false}. The data is the
     * JSON-compatible tree to validate (e.g., model output, bound
     * arguments, or public response data).</p>
     *
     * <p>A report is considered valid only if {@code errors} is empty.
     * Warnings are informational and do not block processing.</p>
     *
     * @param data the JSON-compatible data to validate
     * @param schema the JSON Schema 2020-12 document
     * @return the validation report; valid only if {@code errors} is empty
     */
    ValidationReport validate(Map<String, Object> data, Map<String, Object> schema);
}
