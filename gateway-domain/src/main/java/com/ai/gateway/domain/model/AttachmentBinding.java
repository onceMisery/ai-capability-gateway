package com.ai.gateway.domain.model;

/**
 * 单个协议附件（如 Dubbo attachment key）的绑定。
 *
 * <p>/ 规定附件必须使用平台白名单中的允许键
 *（见 {@link AttachmentWhitelist}）。清单不得定义任意附件名。来源对于平台上下文值
 * 必须是 {@link ArgumentSource#SYSTEM}，对于 {@code delegatedToken} 等白名单键
 * 则使用其他受控来源。</p>
 *
 * <p>未签名的租户、用户或权限附件不参与鉴权。</p>
 *
 * @param source 值来源
 * @param sourcePath 来源内部的 JSON Pointer（如 {@code "/traceId"}）
 * @param converter 可选转换器名
 * @param constantValue CONSTANT 来源的 JSON 常量值
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
     * 紧凑构造器，执行 null 检查。
     *
     * @param source 值来源
     * @param sourcePath 来源 JSON Pointer
     * @param converter 可选转换器名
     * @param constantValue 常量值
     */
    public AttachmentBinding {
        java.util.Objects.requireNonNull(source, "source must not be null");
        if (source != ArgumentSource.CONSTANT) {
            java.util.Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        }
    }
}
