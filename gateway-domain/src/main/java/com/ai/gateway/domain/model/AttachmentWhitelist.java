package com.ai.gateway.domain.model;

import java.util.Set;
import java.util.Collections;

/**
 * The platform whitelist of allowed Dubbo attachment (or equivalent
 * protocol context) keys.
 *
 * <p>Defines the closed set of attachment keys that a Manifest
 * may bind via {@link AttachmentBinding}. Manifests must not define arbitrary
 * attachment names. Unsigned tenant, user, or permission attachments do not
 * participate in authorization.</p>
 *
 * <p>The whitelisted keys are:</p>
 * <ul>
 * <li>{@code traceId} - the distributed trace identifier.</li>
 * <li>{@code deadline} - the request deadline for downstream timeout
 * propagation.</li>
 * <li>{@code locale} - the request locale.</li>
 * <li>{@code delegatedToken} - a short-lived, audience-bound delegated
 * token verified by the Provider.</li>
 * <li>{@code b3-traceid} - B3 trace propagation header.</li>
 * <li>{@code b3-spanid} - B3 span propagation header.</li>
 * <li>{@code rtid} - the trace user identifier key for logging only;
 * never participates in authorization.</li>
 * </ul>
 *
 * <p>If a Provider uses any unsigned key for business identity or tenant
 * determination, the interface is considered non-compliant.</p>
 *
 * @since 0.1.0
 */
public record AttachmentWhitelist() {

    /**
     * The immutable set of allowed attachment keys.
     */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "traceId",
            "deadline",
            "locale",
            "delegatedToken",
            "b3-traceid",
            "b3-spanid",
            "rtid"
    );

    /**
     * Returns an unmodifiable view of the allowed attachment keys.
     *
     * @return the set of whitelisted attachment key names
     */
    public static Set<String> allowedKeys() {
        return Collections.unmodifiableSet(ALLOWED_KEYS);
    }

    /**
     * Returns whether the given key is in the attachment whitelist.
     *
     * @param key the attachment key to check
     * @return {@code true} if the key is whitelisted
     */
    public static boolean isAllowed(String key) {
        return ALLOWED_KEYS.contains(key);
    }
}
