package com.ai.gateway.domain.model;

import java.util.List;

/**
 * Configuration for envelope-mode response unwrapping.
 *
 * <p>Specifies that the gateway cannot depend on business
 * unified-response JARs. Instead, it uses structured JSON Pointer paths
 * to determine business success and extract the data payload:</p>
 *
 * <ul>
 * <li>{@code codePath} - JSON Pointer to the success-code field
 * (e.g., {@code "/code"}).</li>
 * <li>{@code successValues} - the set of values (preserving JSON scalar
 * types) that indicate success. Note: number {@code 0} and string
 * {@code "0"} are distinct; both must be declared if compatibility
 * is required.</li>
 * <li>{@code dataPath} - JSON Pointer to the data payload field
 * (e.g., {@code "/data"} or {@code "/value"}).</li>
 * <li>{@code messagePath} - JSON Pointer to the optional message field
 * (e.g., {@code "/message"}).</li>
 * </ul>
 *
 * <p>Platform standard envelope profile example:</p>
 * <pre>
 * codePath: /code
 * successValues: ["200"]
 * dataPath: /value
 * messagePath: /message
 * </pre>
 *
 * <p>The adapter must recursively strip protocol-metadata keys (e.g.,
 * {@code class}) injected by Dubbo generic invocation before envelope
 * determination, projection, and public Schema validation.</p>
 *
 * @param codePath JSON Pointer to the success-code field
 * @param successValues list of values indicating business success
 * @param dataPath JSON Pointer to the data payload
 * @param messagePath JSON Pointer to the message field; may be null
 * @since 0.1.0
 */
public record EnvelopeConfig(
        String codePath,
        List<Object> successValues,
        String dataPath,
        String messagePath
) {
    /**
     * Compact constructor performing defensive copying.
     *
     * @param codePath JSON Pointer to the success code
     * @param successValues list of success values
     * @param dataPath JSON Pointer to the data
     * @param messagePath JSON Pointer to the message
     */
    public EnvelopeConfig {
        java.util.Objects.requireNonNull(codePath, "codePath must not be null");
        java.util.Objects.requireNonNull(successValues, "successValues must not be null");
        java.util.Objects.requireNonNull(dataPath, "dataPath must not be null");
        successValues = List.copyOf(successValues);
    }
}
