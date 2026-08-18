package com.ai.gateway.domain.model;

/**
 * A single field binding within a composite (object) argument binding.
 *
 * <p>Defines composite bindings for DTOs that simultaneously
 * contain business fields (MODEL-sourced) and trusted fields
 * (PRINCIPAL- or CONSTANT-sourced). Each field is a JSON Pointer to
 * value-source mapping:</p>
 *
 * <pre>
 * /orgId:
 * source: PRINCIPAL
 * sourcePath: /orgId
 * /orderNo:
 * source: MODEL
 * sourcePath: /orderNo
 * /channel:
 * source: CONSTANT
 * value: AI_GATEWAY
 * </pre>
 *
 * <p>The binder must reject:</p>
 * <ul>
 * <li>The same target field being assigned by multiple sources.</li>
 * <li>Assignment to reserved fields: {@code class}, {@code @type}, prototype
 * chain fields, etc.</li>
 * <li>Constants incompatible with the target type.</li>
 * <li>PRINCIPAL paths that do not exist, have mismatched types, or are null.</li>
 * <li>Undeclared fields in the model output.</li>
 * </ul>
 *
 * @param source the value source
 * @param sourcePath the JSON Pointer into the source (e.g., {@code "/orgId"});
 * may be null for CONSTANT source
 * @param converter the optional converter name from the
 * {@link ConverterType} whitelist; null if no conversion
 * @param constantValue the constant value; required for CONSTANT source,
 * null otherwise
 * @since 0.1.0
 */
public record FieldBinding(
        ArgumentSource source,
        String sourcePath,
        String converter,
        Object constantValue
) {

    /**
     * Compact constructor performing null check on the source.
     *
     * @param source the value source
     * @param sourcePath the source JSON Pointer
     * @param converter the optional converter name
     * @param constantValue the constant value
     */
    public FieldBinding {
        java.util.Objects.requireNonNull(source, "source must not be null");
    }
}
