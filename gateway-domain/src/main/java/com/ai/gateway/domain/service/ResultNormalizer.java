package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.EnvelopeConfig;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.ProjectionMapping;
import com.ai.gateway.domain.model.RedactionRule;
import com.ai.gateway.domain.port.SchemaValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes protocol invocation results according to the output contract
 *
 * <p>The result processing pipeline follows the 8-step order defined in
 * :</p>
 * <ol>
 * <li>Convert the protocol result to a JSON-compatible neutral tree.</li>
 * <li>Check response byte size, depth, collection length, and processing
 * deadline.</li>
 * <li>Determine business success by Envelope rules and extract data.</li>
 * <li>Construct the public result by projection whitelist.</li>
 * <li>Execute field deletion, mask, or hash (delegated to
 * {@link RedactionService}).</li>
 * <li>Validate the public output Schema.</li>
 * <li>Generate the structured result.</li>
 * <li>Natural language summary is optional, done by the caller.</li>
 * </ol>
 *
 * <p>For {@link OutputMode#ENVELOPE} mode: the envelope config's
 * {@code codePath} is checked against {@code successValues}; if the code
 * matches a success value, the data at {@code dataPath} is extracted.
 * Otherwise, the result is a business failure (PROVIDER_REJECTED).</p>
 *
 * <p>For {@link OutputMode#DIRECT} mode: the root node is the data
 * directly, without envelope unwrapping.</p>
 *
 * <p>Projection: only fields mapped by projection entries leave the gateway.
 * Unmapped fields are dropped. If no projections are configured, the entire
 * data must match the publicSchema exactly.</p>
 *
 * <p>Any path-not-found, type mismatch, response-too-large, or
 * unable-to-determine-business-success condition results in
 * {@link ErrorCode#PROTOCOL_ERROR}.</p>
 *
 * @since 0.1.0
 */
public final class ResultNormalizer {

    /**
     * Maximum nesting depth for the response tree.
     */
    private static final int MAX_DEPTH = 32;

    /**
     * Maximum collection length in the response.
     */
    private static final int MAX_COLLECTION_LENGTH = 10_000;

    private final OutputContract outputContract;
    private final SchemaValidator schemaValidator;
    private final RedactionService redactionService;

    /**
     * Constructs a new ResultNormalizer with the required dependencies.
     *
     * @param outputContract the output contract defining envelope, projection,
     * redaction, and public schema rules
     * @param schemaValidator the JSON Schema validator for public output
     * validation
     * @param redactionService the field redaction service
     * @throws NullPointerException if any argument is null
     */
    public ResultNormalizer(OutputContract outputContract,
                            SchemaValidator schemaValidator,
                            RedactionService redactionService) {
        this.outputContract = java.util.Objects.requireNonNull(
                outputContract, "outputContract must not be null");
        this.schemaValidator = java.util.Objects.requireNonNull(
                schemaValidator, "schemaValidator must not be null");
        this.redactionService = java.util.Objects.requireNonNull(
                redactionService, "redactionService must not be null");
    }

    /**
     * Normalizes the protocol invocation result according to the output
     * contract.
     *
     * <p>This method executes steps 1-7 of the 8-step processing order.
     * Step 8 (optional natural language summary) is the caller's
     * responsibility.</p>
     *
     * @param result the protocol-neutral invocation result
     * @return the normalized public result as a JSON-compatible map
     * @throws IllegalArgumentException if any processing step fails
     * (path not found, type mismatch,
     * response too large, etc.)
     * @throws NullPointerException if {@code result} is null
     */
    public Map<String, Object> normalize(InvocationResult result) {
        java.util.Objects.requireNonNull(result, "result must not be null");

        // Step 1: Convert protocol result to JSON-compatible neutral tree
        Object neutralTree = toNeutralTree(result);

        // Step 2: Check response byte size, depth, collection length
        checkResponseConstraints(neutralTree);

        // Step 3: Determine business success and extract data
        Object data = extractData(neutralTree, result);

        // Step 4: Construct public result by projection whitelist
        Object publicResult = projectData(data);

        // Step 5: Execute field redaction
        Object redactedResult = redactionService.redact(
                publicResult, outputContract.redactions());

        // Step 6: Validate public output Schema
        validatePublicSchema(redactedResult);

        // Step 7: Generate structured result
        return toStructuredResult(redactedResult, result);
    }

    // -------------------------------------------------------------------------
    // Step 1: Convert to neutral tree
    // -------------------------------------------------------------------------

    /**
     * Converts the invocation result's JSON data to a JSON-compatible
     * neutral tree.
     *
     * <p>The adapter already provides JSON-compatible data in
     * {@link InvocationResult#jsonData()}. This method strips protocol-
     * metadata keys (e.g., {@code class}) injected by Dubbo generic
     * invocation.</p>
     *
     * @param result the invocation result
     * @return the clean neutral tree
     */
    private Object toNeutralTree(InvocationResult result) {
        Object data = result.jsonData();
        if (data == null) {
            throw new IllegalArgumentException(
                    "Protocol error: invocation result data is null; "
                            + "errorCode=" + result.errorCode()
                            + ", errorMessage=" + result.errorMessage()
            );
        }
        return stripMetadataKeys(data);
    }

    /**
     * Recursively strips protocol-metadata keys (e.g., {@code class},
     * {@code @type}) from the neutral tree.
     *
     * @param data the data to clean
     * @return the cleaned data
     */
    @SuppressWarnings("unchecked")
    private Object stripMetadataKeys(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> cleaned = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isMetadataKey(key)) {
                    continue;
                }
                cleaned.put(key, stripMetadataKeys(entry.getValue()));
            }
            return cleaned;
        } else if (data instanceof List<?> list) {
            List<Object> cleaned = new ArrayList<>(list.size());
            for (Object item : list) {
                cleaned.add(stripMetadataKeys(item));
            }
            return cleaned;
        }
        return data;
    }

    /**
     * Checks if a key is a protocol-metadata key that should be stripped.
     *
     * @param key the key to check
     * @return {@code true} if the key is a metadata key
     */
    private boolean isMetadataKey(String key) {
        return "class".equals(key) || "@type".equals(key)
                || "@class".equals(key) || "proto".equals(key);
    }

    // -------------------------------------------------------------------------
    // Step 2: Check response constraints
    // -------------------------------------------------------------------------

    /**
     * Checks the response byte size, depth, and collection length
     *
     * @param data the neutral tree
     * @throws IllegalArgumentException if the response exceeds constraints
     */
    private void checkResponseConstraints(Object data) {
        // Check byte size
        long byteSize = estimateByteSize(data);
        if (outputContract.maxBytes() > 0 && byteSize > outputContract.maxBytes()) {
            throw new IllegalArgumentException(
                    "Response too large: estimated " + byteSize
                            + " bytes exceeds max " + outputContract.maxBytes()
            );
        }

        // Check depth
        int depth = calculateDepth(data, 0);
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Response depth " + depth + " exceeds maximum " + MAX_DEPTH
            );
        }

        // Check collection length
        checkCollectionLength(data);
    }

    /**
     * Estimates the byte size of the JSON-compatible data by serializing
     * to a string approximation.
     *
     * @param data the data
     * @return the estimated byte size
     */
    private long estimateByteSize(Object data) {
        if (data == null) {
            return 4; // "null"
        }
        if (data instanceof String s) {
            return s.length() + 2; // +2 for quotes
        }
        if (data instanceof Map<?, ?> map) {
            long size = 2; // {}
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) size += 1; // comma
                first = false;
                size += String.valueOf(entry.getKey()).length() + 3; // "key":
                size += estimateByteSize(entry.getValue());
            }
            return size;
        }
        if (data instanceof List<?> list) {
            long size = 2; // []
            boolean first = true;
            for (Object item : list) {
                if (!first) size += 1; // comma
                first = false;
                size += estimateByteSize(item);
            }
            return size;
        }
        return String.valueOf(data).length();
    }

    /**
     * Calculates the maximum nesting depth of the data tree.
     *
     * @param data the data
     * @param current the current depth
     * @return the maximum depth
     */
    private int calculateDepth(Object data, int current) {
        if (current > MAX_DEPTH) {
            return current;
        }
        if (data instanceof Map<?, ?> map) {
            int maxChild = current;
            for (Object value : map.values()) {
                int childDepth = calculateDepth(value, current + 1);
                if (childDepth > maxChild) {
                    maxChild = childDepth;
                }
            }
            return maxChild;
        }
        if (data instanceof List<?> list) {
            int maxChild = current;
            for (Object item : list) {
                int childDepth = calculateDepth(item, current + 1);
                if (childDepth > maxChild) {
                    maxChild = childDepth;
                }
            }
            return maxChild;
        }
        return current;
    }

    /**
     * Checks that no collection in the data exceeds the maximum length.
     *
     * @param data the data
     * @throws IllegalArgumentException if a collection exceeds the limit
     */
    private void checkCollectionLength(Object data) {
        if (data instanceof Map<?, ?> map) {
            if (map.size() > MAX_COLLECTION_LENGTH) {
                throw new IllegalArgumentException(
                        "Map size " + map.size()
                                + " exceeds maximum collection length " + MAX_COLLECTION_LENGTH
                );
            }
            for (Object value : map.values()) {
                checkCollectionLength(value);
            }
        } else if (data instanceof List<?> list) {
            if (list.size() > MAX_COLLECTION_LENGTH) {
                throw new IllegalArgumentException(
                        "List size " + list.size()
                                + " exceeds maximum collection length " + MAX_COLLECTION_LENGTH
                );
            }
            for (Object item : list) {
                checkCollectionLength(item);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 3: Determine business success and extract data
    // -------------------------------------------------------------------------

    /**
     * Determines business success by Envelope rules and extracts the data
     * payload.
     *
     * <p>For {@link OutputMode#ENVELOPE}: checks the code at
     * {@code codePath} against {@code successValues}. If the code matches,
     * extracts data at {@code dataPath}. Otherwise, treats the result as
     * a business failure (PROVIDER_REJECTED).</p>
     *
     * <p>For {@link OutputMode#DIRECT}: uses the root node as the data
     * directly.</p>
     *
     * @param neutralTree the clean neutral tree
     * @param result the invocation result (for error context)
     * @return the extracted data payload
     * @throws IllegalArgumentException if the envelope code path is not found,
     * the code does not match any success
     * value, or the data path is not found
     */
    private Object extractData(Object neutralTree, InvocationResult result) {
        if (outputContract.mode() == OutputMode.DIRECT) {
            return neutralTree;
        }

        EnvelopeConfig envelope = outputContract.envelope();
        if (envelope == null) {
            throw new IllegalArgumentException(
                    "Protocol error: ENVELOPE mode requires an envelope config"
            );
        }

        // Read the success code
        Object codeValue = resolvePath(neutralTree, envelope.codePath());
        if (codeValue == null) {
            throw new IllegalArgumentException(
                    "Protocol error: success code path '" + envelope.codePath()
                            + "' not found in response"
            );
        }

        // Check if code matches a success value
        boolean success = false;
        for (Object successValue : envelope.successValues()) {
            if (valuesEqual(codeValue, successValue)) {
                success = true;
                break;
            }
        }

        if (!success) {
            throw new IllegalArgumentException(
                    "Business failure: envelope code " + codeValue
                            + " does not match any success value " + envelope.successValues()
            );
        }

        // Extract data from dataPath
        Object data = resolvePath(neutralTree, envelope.dataPath());
        if (data == null) {
            throw new IllegalArgumentException(
                    "Protocol error: data path '" + envelope.dataPath()
                            + "' not found in response"
            );
        }

        return data;
    }

    /**
     * Compares two values for envelope success matching, accounting for
     * type coercion (e.g., number 0 vs string "0").
     *
     * @param actual the actual value from the response
     * @param expected the expected success value
     * @return {@code true} if they are equal
     */
    private boolean valuesEqual(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (actual.equals(expected)) {
            return true;
        }
        // Compare as strings to handle number/string mismatches
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    // -------------------------------------------------------------------------
    // Step 4: Construct public result by projection
    // -------------------------------------------------------------------------

    /**
     * Constructs the public result by applying the projection whitelist
     *
     * <p>Only fields mapped by projection entries leave the gateway.
     * Unmapped fields are dropped. If no projections are configured, the
     * entire data is returned as-is (it must then match the publicSchema
     * exactly in step 6).</p>
     *
     * @param data the extracted data payload
     * @return the projected public result
     */
    private Object projectData(Object data) {
        List<ProjectionMapping> projections = outputContract.projections();
        if (projections.isEmpty()) {
            return data;
        }

        Map<String, Object> projected = new LinkedHashMap<>(projections.size());
        for (ProjectionMapping mapping : projections) {
            Object value = resolvePath(data, mapping.from());
            if (value == null) {
                throw new IllegalArgumentException(
                        "Protocol error: projection source path '" + mapping.from()
                                + "' not found in data"
                );
            }
            setNestedValue(projected, mapping.to(), value);
        }
        return projected;
    }

    // -------------------------------------------------------------------------
    // Step 6: Validate public output Schema
    // -------------------------------------------------------------------------

    /**
     * Validates the redacted public result against the public output Schema
     *
     * @param redactedResult the redacted public result
     * @throws IllegalArgumentException if the schema validation fails
     */
    @SuppressWarnings("unchecked")
    private void validatePublicSchema(Object redactedResult) {
        Map<String, Object> publicSchema = outputContract.publicSchema();
        if (publicSchema.isEmpty()) {
            return;
        }

        Map<String, Object> dataToValidate;
        if (redactedResult instanceof Map<?, ?>) {
            dataToValidate = (Map<String, Object>) redactedResult;
        } else {
            // Wrap scalar values in a root map for validation
            dataToValidate = new LinkedHashMap<>();
            dataToValidate.put("value", redactedResult);
        }

        com.ai.gateway.domain.model.ValidationReport report =
                schemaValidator.validate(dataToValidate, publicSchema);
        if (!report.valid()) {
            throw new IllegalArgumentException(
                    "Public output Schema validation failed: " + report.errors()
            );
        }
    }

    // -------------------------------------------------------------------------
    // Step 7: Generate structured result
    // -------------------------------------------------------------------------

    /**
     * Generates the structured result from the redacted and validated data
     *
     * @param redactedResult the redacted public result
     * @param invocationResult the original invocation result for metadata
     * @return the structured result map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toStructuredResult(Object redactedResult,
                                                   InvocationResult invocationResult) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("success", true);
        structured.put("data", redactedResult);

        Map<String, String> metadata = invocationResult.metadata();
        if (metadata != null && !metadata.isEmpty()) {
            structured.put("metadata", new LinkedHashMap<>(metadata));
        }

        return structured;
    }

    // -------------------------------------------------------------------------
    // JSON Pointer utilities
    // -------------------------------------------------------------------------

    /**
     * Resolves a JSON Pointer (RFC 6901) path in the data tree.
     *
     * @param data the data tree
     * @param pointer the JSON Pointer
     * @return the resolved value, or {@code null} if not found
     */
    @SuppressWarnings("unchecked")
    private Object resolvePath(Object data, String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return data;
        }

        List<String> tokens = parseJsonPointer(pointer);
        Object current = data;

        for (String token : tokens) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    return null;
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                int idx;
                try {
                    idx = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (idx < 0 || idx >= list.size()) {
                    return null;
                }
                current = list.get(idx);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Sets a nested value in the result map using a JSON Pointer path.
     *
     * @param result the result map
     * @param pointer the JSON Pointer
     * @param value the value to set
     */
    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> result, String pointer, Object value) {
        List<String> tokens = parseJsonPointer(pointer);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException(
                    "Empty projection target path"
            );
        }

        Map<String, Object> current = result;
        for (int i = 0; i < tokens.size() - 1; i++) {
            String token = tokens.get(i);
            Object child = current.get(token);
            if (child == null) {
                child = new LinkedHashMap<String, Object>();
                current.put(token, child);
            } else if (!(child instanceof Map)) {
                throw new IllegalArgumentException(
                        "Cannot navigate to '" + pointer
                                + "': intermediate segment '" + token
                                + "' is not a map"
                );
            }
            current = (Map<String, Object>) child;
        }
        current.put(tokens.get(tokens.size() - 1), value);
    }

    /**
     * Parses a JSON Pointer (RFC 6901) string into reference tokens.
     *
     * @param pointer the JSON Pointer string
     * @return the list of reference tokens
     */
    private List<String> parseJsonPointer(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return List.of();
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Invalid JSON Pointer: must start with '/' or be empty: " + pointer
            );
        }
        String[] parts = pointer.split("/", -1);
        List<String> tokens = new ArrayList<>(parts.length - 1);
        for (int i = 1; i < parts.length; i++) {
            tokens.add(unescapeToken(parts[i]));
        }
        return tokens;
    }

    /**
     * Unescapes a JSON Pointer reference token per RFC 6901.
     *
     * @param token the raw token
     * @return the unescaped token
     */
    private String unescapeToken(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }
}
