package com.ai.gateway.adapter.dubbo;

import java.util.Collections;
import java.util.Set;

/**
 * Serialization whitelist for Dubbo generic invocation.
 *
 * <p>The serialization method is a capability-level configuration declared
 * in the Manifest's protocol binding. The declared value must belong to
 * this platform-maintained whitelist. Only serialization implementations
 * shipped with Apache Dubbo community releases are permitted.</p>
 *
 * <p>The whitelist currently contains:</p>
 * <ul>
 * <li>{@code hessian2} - Apache Dubbo's default Hessian2 serialization</li>
 * <li>{@code fastjson2} - Fastjson2 serialization</li>
 * </ul>
 *
 * <p>Internal custom serialization extensions (custom serialization IDs in
 * private artifacts) do not enter the whitelist: introducing them requires
 * depending on internal JARs, violating the independence boundary
 * (Section 4). If a target service only supports internal custom
 * serialization, it must first expose a standard serialization negotiation
 * capability before it can be onboarded.</p>
 *
 * @since 0.1.0
 */
public final class SerializationWhitelist {

    /**
     * The immutable set of allowed serialization methods.
     */
    private static final Set<String> ALLOWED = Collections.unmodifiableSet(
            Set.of("hessian2", "fastjson2"));

    private SerializationWhitelist() {
        // Utility class — not instantiable
    }

    /**
     * Returns whether the given serialization method is in the whitelist.
     *
     * @param serialization the serialization method name to check
     * @return {@code true} if the serialization method is whitelisted
     */
    public static boolean isAllowed(String serialization) {
        if (serialization == null) {
            return false;
        }
        return ALLOWED.contains(serialization);
    }

    /**
     * Validates that the given serialization method is in the whitelist.
     *
     * @param serialization the serialization method name to validate
     * @throws IllegalArgumentException if the serialization method is not
     * in the whitelist
     * @throws NullPointerException if serialization is null
     */
    public static void validate(String serialization) {
        java.util.Objects.requireNonNull(serialization,
                "serialization must not be null");
        if (!ALLOWED.contains(serialization)) {
            throw new IllegalArgumentException(
                    "Serialization method '" + serialization
                            + "' is not in the platform whitelist. "
                            + "Allowed: " + ALLOWED
                            + "");
        }
    }

    /**
     * Returns an unmodifiable view of the allowed serialization methods.
     *
     * @return the set of whitelisted serialization method names
     */
    public static Set<String> allowedValues() {
        return ALLOWED;
    }
}
