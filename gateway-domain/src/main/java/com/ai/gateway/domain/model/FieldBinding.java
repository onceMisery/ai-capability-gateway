package com.ai.gateway.domain.model;

/**
 * 复合（对象）参数绑定中的单个字段绑定。
 *
 * <p>为同时包含业务字段（MODEL 来源）与受信任字段（PRINCIPAL 或 CONSTANT 来源）的
 * DTO 定义复合绑定。每个字段都是指向"值-来源"映射的 JSON Pointer：</p>
 *
 * <pre>
 * /orgId:
 * source: PRINCIPAL
 * sourcePath: /orgId
 * /orderNo:
 * source: MODEL
 * sourcePath: /orderNo
 * /channel:
 * source: CONSTANT
 * value: AI_GATEWAY
 * </pre>
 *
 * <p>绑定器必须拒绝：</p>
 * <ul>
 * <li>被多个来源赋值的同一目标字段。</li>
 * <li>对保留字段的赋值：{@code class}、{@code @type}、原型链字段等。</li>
 * <li>与目标类型不兼容的常量。</li>
 * <li>不存在、类型不匹配或值为 null 的 PRINCIPAL 路径。</li>
 * <li>模型输出中未声明的字段。</li>
 * </ul>
 *
 * @param source 值来源
 * @param sourcePath 指向来源的 JSON Pointer（如 {@code "/orgId"}）；
 * CONSTANT 来源时可为 null
 * @param converter 可选的转换器名，取自 {@link ConverterType} 白名单；无转换时为 null
 * @param constantValue 常量值；CONSTANT 来源时必填，否则为 null
 * @since 0.1.0
 */
public record FieldBinding(
        ArgumentSource source,
        String sourcePath,
        String converter,
        Object constantValue
) {

    /**
     * 紧凑构造器，对来源执行 null 检查。
     *
     * @param source 值来源
     * @param sourcePath 来源 JSON Pointer
     * @param converter 可选转换器名
     * @param constantValue 常量值
     */
    public FieldBinding {
        java.util.Objects.requireNonNull(source, "source must not be null");
    }
}
