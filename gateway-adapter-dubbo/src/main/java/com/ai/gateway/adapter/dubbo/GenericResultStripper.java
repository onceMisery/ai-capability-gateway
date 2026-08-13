package com.ai.gateway.adapter.dubbo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursively strips protocol metadata keys from Dubbo generic invocation
 * results.
 *
 * <p>When the Provider returns a Map via Dubbo generic invocation, it
 * carries protocol metadata keys such as {@code class}. The adapter must
 * recursively strip these keys before constructing a neutral JSON tree,
 * as they must not enter Envelope judgment, projection, Schema validation,
 * or any external output.</p>
 *
 * <p>Stripping must occur BEFORE:</p>
 * <ul>
 * <li>Envelope judgment</li>
 * <li>Projection whitelist application</li>
 * <li>Schema validation</li>
 * <li>Any external output</li>
 * </ul>
 *
 * <p>The stripping is recursive: for Map values, the {@code class} key is
 * removed and remaining values are recursively processed. For List values,
 * each element is recursively processed. Primitive values are returned
 * as-is.</p>
 *
 * @since 0.1.0
 */
@Component
public class GenericResultStripper {

    private static final Logger log = LoggerFactory.getLogger(GenericResultStripper.class);

    /**
     * The protocol metadata key that Dubbo adds to generic invocation
     * Map results to indicate the original Java type.
     */
    private static final String CLASS_KEY = "class";

    /**
     * Additional protocol metadata key that some serialization frameworks
     * may add to generic invocation results.
     */
    private static final String TYPE_KEY = "@type";

    /**
     * Recursively strips protocol metadata keys from the result.
     *
     * <p>Processing rules:</p>
     * <ul>
     * <li>If the result is a {@link Map}: remove the {@code class} and
     * {@code @type} keys, then recursively process all remaining
     * values.</li>
     * <li>If the result is a {@link List}: recursively process each
     * element.</li>
     * <li>If the result is any other type: return it as-is.</li>
     * </ul>
     *
     * @param result the raw Dubbo generic invocation result
     * @return the stripped result with protocol metadata keys removed
     */
    @SuppressWarnings("unchecked")
    public Object strip(Object result) {
        if (result == null) {
            return null;
        }

        if (result instanceof Map<?, ?> rawMap) {
            Map<String, Object> cleaned = new LinkedHashMap<>(rawMap.size());
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                // Strip protocol metadata keys
                if (CLASS_KEY.equals(key) || TYPE_KEY.equals(key)) {
                    continue;
                }
                // Recursively strip nested values
                cleaned.put(key, strip(entry.getValue()));
            }
            return cleaned;
        }

        if (result instanceof List<?> rawList) {
            List<Object> cleaned = new ArrayList<>(rawList.size());
            for (Object element : rawList) {
                cleaned.add(strip(element));
            }
            return cleaned;
        }

        if (result.getClass().isArray()) {
            // Handle primitive arrays — return as-is, they are JSON-compatible
            // Object arrays are recursively processed
            if (result instanceof Object[] rawArray) {
                Object[] cleaned = new Object[rawArray.length];
                for (int i = 0; i < rawArray.length; i++) {
                    cleaned[i] = strip(rawArray[i]);
                }
                return cleaned;
            }
            // Primitive arrays (int[], long[], etc.) are JSON-compatible
            return result;
        }

        // Primitive types, String, Number, Boolean — return as-is
        return result;
    }
}
