package com.ai.gateway.domain.model;

/**
 * A binding for a single protocol attachment (e.g., Dubbo attachment key).
 *
 * <p>/ define that attachments must use the
 * platform whitelist of allowed keys (see {@link AttachmentWhitelist}).
 * Manifests must not define arbitrary attachment names. The source
 * must be {@link ArgumentSource#SYSTEM} for platform context values,
 * or other controlled sources for whitelisted keys like
 * {@code delegatedToken}.</p>
 *
 * <p>Unsigned tenant, user, or permission attachments do not participate
 * in authorization.</p>
 *
 * @param source the value source
 * @param sourcePath the JSON Pointer into the source (e.g., {@code "/traceId"})
 * @since 0.1.0
 */
public record AttachmentBinding(
        ArgumentSource source,
        String sourcePath
) {

    /**
     * Compact constructor performing null checks.
     *
     * @param source the value source
     * @param sourcePath the source JSON Pointer
     */
    public AttachmentBinding {
        java.util.Objects.requireNonNull(source, "source must not be null");
        java.util.Objects.requireNonNull(sourcePath, "sourcePath must not be null");
    }
}
