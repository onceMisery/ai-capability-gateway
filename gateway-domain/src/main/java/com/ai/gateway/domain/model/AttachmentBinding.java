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
 * @param converter the optional converter name
 * @param constantValue the JSON constant value for CONSTANT source
 * @since 0.1.0
 */
public record AttachmentBinding(
        ArgumentSource source,
        String sourcePath,
        String converter,
        Object constantValue
) {

    /**
     * 创建不需要转换器和常量值的附件绑定。
     *
     * @param source 值来源
     * @param sourcePath 来源路径
     */
    public AttachmentBinding(ArgumentSource source, String sourcePath) {
        this(source, sourcePath, null, null);
    }

    /**
     * Compact constructor performing null checks.
     *
     * @param source the value source
     * @param sourcePath the source JSON Pointer
     * @param converter the optional converter name
     * @param constantValue the constant value
     */
    public AttachmentBinding {
        java.util.Objects.requireNonNull(source, "source must not be null");
        if (source != ArgumentSource.CONSTANT) {
            java.util.Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        }
    }
}
