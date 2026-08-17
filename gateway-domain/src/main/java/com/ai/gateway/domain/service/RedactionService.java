package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.RedactionMethod;
import com.ai.gateway.domain.model.RedactionRule;

import java.util.List;
import java.util.Map;

/**
 * Applies field-level redaction rules to projected output data
 *
 * <p>After the projection whitelist constructs the public result
 * the gateway applies declarative redaction rules
 * before the public output Schema validation.
 * Redactions are deterministic and do not involve scripts or arbitrary
 * expressions.</p>
 *
 * <p>Supported redaction methods ({@link RedactionMethod}):</p>
 * <ul>
 * <li><strong>PARTIAL_MASK</strong> — mask the middle portion of a string
 * value, keeping the first 2 and last 2 characters. Short strings
 * (4 characters or fewer) are fully masked.</li>
 * <li><strong>HASH</strong> — replace the field value with a SHA-256 hash,
 * hex-encoded. The hash is deterministic and configured by the
 * platform, not by the Manifest.</li>
 * <li><strong>DELETE</strong> — remove the field entirely. The field key
 * is removed from the parent object; the return value is {@code null}
 * at that path.</li>
 * </ul>
 *
 * <p>The service uses JSON Pointer (RFC 6901) to navigate to the target path
 * within the data tree. Navigation is recursive for nested objects.</p>
 *
 * <p>This class is thread-safe: it holds no mutable state and each call
 * delegates HASH operations to {@link Sha256Digest}.</p>
 *
 * @see RedactionRule
 * @see RedactionMethod
 * @since 0.1.0
 */
public final class RedactionService {

    /**
     * The number of characters to keep at the start and end for
     * PARTIAL_MASK.
     */
    private static final int MASK_KEEP_PREFIX = 2;
    private static final int MASK_KEEP_SUFFIX = 2;

    /**
     * Applies all redaction rules to the given data tree
     *
     * <p>Rules are applied in order. Each rule navigates to its target path
     * using JSON Pointer (RFC 6901) and applies the configured method.
     * The data tree is traversed recursively; modifications produce a new
     * tree (the original is not mutated if it consists of immutable
     * collections).</p>
     *
     * @param data the JSON-compatible data tree; must not be null
     * @param rules the redaction rules to apply; may be empty but not null
     * @return the redacted data tree; may be {@code null} if all content
     * was deleted
     * @throws NullPointerException if {@code data} or {@code rules} is null
     */
    public Object redact(Object data, List<RedactionRule> rules) {
        java.util.Objects.requireNonNull(data, "data must not be null");
        java.util.Objects.requireNonNull(rules, "rules must not be null");

        Object result = data;
        for (RedactionRule rule : rules) {
            result = applyRule(result, rule);
        }
        return result;
    }

    /**
     * Applies a single redaction rule to the data tree.
     *
     * @param data the current data tree
     * @param rule the rule to apply
     * @return the modified data tree
     */
    @SuppressWarnings("unchecked")
    private Object applyRule(Object data, RedactionRule rule) {
        List<String> tokens = parseJsonPointer(rule.path());

        // Root-level path (empty pointer "")
        if (tokens.isEmpty()) {
            return applyMethod(data, rule.method());
        }

        return applyAtPath(data, tokens, 0, rule.method());
    }

    /**
     * Recursively navigates to the target path and applies the redaction
     * method at the leaf.
     *
     * @param current the current node
     * @param tokens the path tokens
     * @param index the current token index
     * @param method the redaction method
     * @return the modified node
     */
    @SuppressWarnings("unchecked")
    private Object applyAtPath(Object current, List<String> tokens, int index,
                               RedactionMethod method) {
        if (index >= tokens.size()) {
            return applyMethod(current, method);
        }

        String token = tokens.get(index);

        if (current instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) current;
            if (!map.containsKey(token)) {
                return current;
            }

            // Create a mutable copy
            Map<String, Object> copy = new java.util.LinkedHashMap<>(map);
            Object child = copy.get(token);

            if (index == tokens.size() - 1) {
                // Leaf node
                if (method == RedactionMethod.DELETE) {
                    copy.remove(token);
                } else {
                    copy.put(token, applyMethod(child, method));
                }
            } else {
                copy.put(token, applyAtPath(child, tokens, index + 1, method));
            }
            return copy;
        } else if (current instanceof List) {
            List<Object> list = (List<Object>) current;
            int arrayIndex;
            try {
                arrayIndex = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                return current;
            }
            if (arrayIndex < 0 || arrayIndex >= list.size()) {
                return current;
            }

            List<Object> copy = new java.util.ArrayList<>(list);
            Object child = copy.get(arrayIndex);

            if (index == tokens.size() - 1) {
                if (method == RedactionMethod.DELETE) {
                    copy.remove(arrayIndex);
                } else {
                    copy.set(arrayIndex, applyMethod(child, method));
                }
            } else {
                copy.set(arrayIndex, applyAtPath(child, tokens, index + 1, method));
            }
            return copy;
        }

        return current;
    }

    /**
     * Applies the redaction method to a leaf value.
     *
     * @param value the original value
     * @param method the redaction method
     * @return the redacted value; may be null for DELETE
     */
    private Object applyMethod(Object value, RedactionMethod method) {
        switch (method) {
            case PARTIAL_MASK:
                return partialMask(value);
            case HASH:
                return hashValue(value);
            case DELETE:
                return null;
            default:
                throw new IllegalArgumentException(
                        "Unknown redaction method: " + method
                );
        }
    }

    /**
     * Partially masks a string value, keeping the first 2 and last 2
     * characters. Non-string values are converted to string first.
     *
     * <p>For strings of 4 characters or fewer, the entire value is masked
     * with asterisks.</p>
     *
     * @param value the value to mask
     * @return the masked string
     */
    private String partialMask(Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString();
        int len = str.length();

        if (len <= MASK_KEEP_PREFIX + MASK_KEEP_SUFFIX) {
            return "*".repeat(len > 0 ? len : 1);
        }

        String prefix = str.substring(0, MASK_KEEP_PREFIX);
        String suffix = str.substring(len - MASK_KEEP_SUFFIX);
        int maskLength = len - MASK_KEEP_PREFIX - MASK_KEEP_SUFFIX;
        return prefix + "*".repeat(maskLength) + suffix;
    }

    /**
     * Computes the SHA-256 hash of the value, hex-encoded.
     *
     * @param value the value to hash
     * @return the hex-encoded SHA-256 hash string
     */
    private String hashValue(Object value) {
        if (value == null) {
            return null;
        }
        return Sha256Digest.sha256Hex(value.toString());
    }

    /**
     * Parses a JSON Pointer (RFC 6901) string into a list of reference
     * tokens.
     *
     * <p>Per RFC 6901:</p>
     * <ul>
     * <li>An empty string {@code ""} refers to the root (returns an empty
     * list).</li>
     * <li>{@code "/"} refers to a key that is an empty string.</li>
     * <li>{@code "~1"} in a token is unescaped to {@code "/"}.</li>
     * <li>{@code "~0"} in a token is unescaped to {@code "~"}.</li>
     * </ul>
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
        List<String> tokens = new java.util.ArrayList<>(parts.length - 1);
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
