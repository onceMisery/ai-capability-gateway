package com.ai.gateway.adapter.dubbo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Constructs generic invocation arguments for Dubbo.
 *
 * <p>Dubbo generic invocation uses Map/List/primitive structures instead of
 * loading business API JARs. For POJO parameter types, the adapter creates a
 * {@link HashMap} with the {@code class} field set to the confirmed
 * {@code protocolType} from the Manifest. User and model must NOT write
 * {@code class} or {@code @type} fields — the adapter generates type
 * metadata exclusively from the confirmed protocol type.</p>
 *
 * <p>The method signature is:</p>
 * <pre>
 * Object[] buildArguments(List&lt;Object&gt; boundArguments, List&lt;String&gt; parameterTypes)
 * </pre>
 *
 * <p>Where {@code boundArguments} are the fully-resolved, positionally-ordered
 * protocol arguments and {@code parameterTypes} are the exact type-name
 * strings from the interface declaration. The adapter does NOT call
 * {@code Class.forName}.</p>
 *
 * @since 0.1.0
 */
@Component
public class GenericArgumentBuilder {

    private static final Logger log = LoggerFactory.getLogger(GenericArgumentBuilder.class);

    /**
     * The type metadata key for Dubbo generic invocation POJO parameters.
     */
    private static final String CLASS_KEY = "class";

    /**
     * Additional reserved field that must not be written by user or model.
     */
    private static final String TYPE_KEY = "@type";

    /**
     * Java primitive and wrapper types that are passed as-is without
     * wrapping in a Map. These types do not require type metadata for
     * Dubbo generic invocation.
     */
    private static final Set<String> SIMPLE_TYPES = Set.of(
            "int", "long", "boolean", "double", "float", "byte", "short", "char",
            "java.lang.String",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Boolean",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Character",
            "java.lang.Number",
            "java.lang.Object",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.Date",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.LocalTime",
            "java.time.Instant",
            "java.time.ZonedDateTime",
            "java.util.Map",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.List",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.Set",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.Collection",
            "java.lang.Enum"
    );

    /**
     * Builds the argument array for Dubbo generic invocation.
     *
     * <p>For each argument at position {@code i}:</p>
     * <ul>
     * <li>If {@code parameterTypes[i]} is a primitive/wrapper/String/Map/List
     * type: the argument is passed as-is.</li>
     * <li>If {@code parameterTypes[i]} is a POJO type: the argument is
     * wrapped in a {@link HashMap} with the {@code class} field set
     * to the protocolType. Any existing {@code class} or
     * {@code @type} keys from user/model output are removed first
     *.</li>
     * </ul>
     *
     * @param boundArguments the fully-resolved, positionally-ordered arguments
     * @param parameterTypes the exact type-name strings from the interface
     * declaration
     * @return the argument array for {@code genericService.$invoke}
     * @throws NullPointerException if either parameter is null
     * @throws IllegalArgumentException if the argument and type lists have
     * different lengths
     */
    public Object[] buildArguments(List<Object> boundArguments, List<String> parameterTypes) {
        Objects.requireNonNull(boundArguments, "boundArguments must not be null");
        Objects.requireNonNull(parameterTypes, "parameterTypes must not be null");

        if (boundArguments.size() != parameterTypes.size()) {
            throw new IllegalArgumentException(
                    "Argument count (" + boundArguments.size()
                            + ") does not match parameter type count ("
                            + parameterTypes.size() + ")");
        }

        Object[] result = new Object[boundArguments.size()];
        for (int i = 0; i < boundArguments.size(); i++) {
            Object arg = boundArguments.get(i);
            String protocolType = parameterTypes.get(i);
            result[i] = buildSingleArgument(arg, protocolType);
        }

        log.debug("Built {} generic arguments for Dubbo invocation", result.length);
        return result;
    }

    /**
     * Builds a single generic argument for the given value and protocol type.
     *
     * @param arg the bound argument value
     * @param protocolType the fully-qualified Java type name string
     * @return the generic invocation argument
     */
    @SuppressWarnings("unchecked")
    private Object buildSingleArgument(Object arg, String protocolType) {
        if (arg == null) {
            return null;
        }

        // Simple types are passed as-is
        if (SIMPLE_TYPES.contains(protocolType)) {
            return arg;
        }

        // For array types, pass as-is (Dubbo handles arrays natively)
        if (protocolType.endsWith("[]")) {
            return arg;
        }

        // POJO type: wrap in HashMap with "class" field set to protocolType
        if (arg instanceof Map<?, ?> mapArg) {
            Map<String, Object> genericMap = new LinkedHashMap<>(mapArg.size() + 1);
            for (Map.Entry<?, ?> entry : mapArg.entrySet()) {
                String key = String.valueOf(entry.getKey());
                // User and model must NOT write class, @type fields
                // Remove any that were injected (defense in depth)
                if (CLASS_KEY.equals(key) || TYPE_KEY.equals(key)) {
                    log.warn("Removing reserved '{}' key from model/user output", key);
                    continue;
                }
                genericMap.put(key, entry.getValue());
            }
            // Adapter generates type metadata from confirmed protocolType
            genericMap.put(CLASS_KEY, protocolType);
            return genericMap;
        }

        // If the argument is not a Map but the type is a POJO, we cannot
        // construct the proper generic representation. This should not
        // normally happen since model output is JSON-compatible (Map/List/primitives).
        log.warn("Argument for POJO type '{}' is not a Map (actual: {}), "
                        + "wrapping with class metadata only",
                protocolType, arg.getClass().getName());
        Map<String, Object> genericMap = new HashMap<>();
        genericMap.put(CLASS_KEY, protocolType);
        return genericMap;
    }
}
