package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * The binding definition for a single protocol argument at a given position.
 *
 * <p>Defines two binding modes:</p>
 *
 * <h3>Simple binding</h3>
 * <p>Uses {@code source} and {@code sourcePath} to read a value from a
 * single controlled source:</p>
 * <pre>
 * - position: 0
 * name: orgId
 * protocolType: java.lang.Long
 * source: PRINCIPAL
 * sourcePath: /orgId
 * </pre>
 *
 * <h3>Composite binding</h3>
 * <p>Uses {@code objectBindings} — a map of JSON Pointer to {@link FieldBinding}
 * — when a DTO contains both business fields and trusted fields. This is a
 * static mapping; no SpEL, scripts, or arbitrary expressions are allowed
 *.</p>
 * <pre>
 * - position: 1
 * name: request
 * protocolType: com.example.order.api.OrderQueryRequest
 * object:
 * /orgId:
 * source: PRINCIPAL
 * sourcePath: /orgId
 * /orderNo:
 * source: MODEL
 * sourcePath: /orderNo
 * </pre>
 *
 * <p>A parameter may use simple binding <em>or</em> composite binding, but
 * not both. The binder must reject:</p>
 * <ul>
 * <li>Duplicate positions, non-contiguous positions, or positions
 * inconsistent with the protocol signature.</li>
 * <li>PRINCIPAL paths that do not exist, have mismatched types, or
 * have null values.</li>
 * <li>Undeclared fields in the model output.</li>
 * <li>The same target field assigned by multiple sources.</li>
 * <li>Assignment to {@code class}, {@code @type}, or other reserved fields.</li>
 * <li>Constants incompatible with the target type.</li>
 * </ul>
 *
 * @param position the zero-based argument position in the protocol method signature
 * @param name the argument name (for documentation)
 * @param protocolType the fully-qualified Java type name as a string
 * (e.g., {@code "java.lang.Long"}); the gateway does
 * not load this class
 * @param source the value source for simple binding; null for composite binding
 * @param sourcePath the JSON Pointer into the source for simple binding
 * @param converter the optional converter name for simple binding; null if none
 * @param constantValue the constant value for CONSTANT simple binding
 * @param objectBindings the JSON Pointer to {@link FieldBinding} map for
 * composite binding; null for simple binding
 * @since 0.1.0
 */
public record ArgumentBinding(
        int position,
        String name,
        String protocolType,
        ArgumentSource source,
        String sourcePath,
        String converter,
        Object constantValue,
        Map<String, FieldBinding> objectBindings
) {

    /**
     * Compact constructor performing defensive copying and basic validation.
     *
     * @param position the argument position
     * @param name the argument name
     * @param protocolType the protocol type name string
     * @param source the value source for simple binding
     * @param sourcePath the source path for simple binding
     * @param converter the optional converter name
     * @param constantValue the constant value
     * @param objectBindings the composite binding map
     */
    public ArgumentBinding {
        java.util.Objects.requireNonNull(name, "name must not be null");
        java.util.Objects.requireNonNull(protocolType, "protocolType must not be null");
        if (objectBindings != null) {
            objectBindings = Map.copyOf(objectBindings);
        }
    }

    /**
     * Returns whether this argument uses composite binding.
     *
     * @return {@code true} if {@code objectBindings} is non-null and non-empty
     */
    public boolean isComposite() {
        return objectBindings != null && !objectBindings.isEmpty();
    }
}
