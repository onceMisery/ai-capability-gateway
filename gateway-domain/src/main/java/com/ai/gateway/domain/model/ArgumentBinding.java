package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * 在指定位置上的单个协议参数绑定定义。
 *
 * <p>定义两种绑定模式：</p>
 *
 * <h3>简单绑定</h3>
 * <p>使用 {@code source} 与 {@code sourcePath} 从单一受控来源读取值：</p>
 * <pre>
 * - position: 0
 * name: orgId
 * protocolType: java.lang.Long
 * source: PRINCIPAL
 * sourcePath: /orgId
 * </pre>
 *
 * <h3>复合绑定</h3>
 * <p>当 DTO 同时包含业务字段与受信任字段时，使用 {@code objectBindings}
 * —— 一个从 JSON Pointer 到 {@link FieldBinding} 的映射。这是一种静态映射，
 * 不允许 SpEL、脚本或任意表达式。</p>
 * <pre>
 * - position: 1
 * name: request
 * protocolType: com.example.order.api.OrderQueryRequest
 * object:
 * /orgId:
 * source: PRINCIPAL
 * sourcePath: /orgId
 * /orderNo:
 * source: MODEL
 * sourcePath: /orderNo
 * </pre>
 *
 * <p>一个参数可以使用简单绑定<em>或</em>复合绑定，但不能同时使用。绑定器必须拒绝：</p>
 * <ul>
 * <li>重复的位置、不连续的位置，或与协议签名不一致的位置。</li>
 * <li>不存在、类型不匹配或值为 null 的 PRINCIPAL 路径。</li>
 * <li>模型输出中未声明的字段。</li>
 * <li>被多个来源赋值的同一目标字段。</li>
 * <li>对 {@code class}、{@code @type} 或其他保留字段的赋值。</li>
 * <li>与目标类型不兼容的常量。</li>
 * </ul>
 *
 * @param position 协议方法签名中从 0 开始的参数位置
 * @param name 参数名（用于文档）
 * @param protocolType 以字符串形式给出的全限定 Java 类型名
 *（如 {@code "java.lang.Long"}）；网关不会加载该类
 * @param source 简单绑定的值来源；复合绑定时为 null
 * @param sourcePath 简单绑定的来源内部 JSON Pointer
 * @param converter 简单绑定的可选转换器名；无则为 null
 * @param constantValue CONSTANT 简单绑定的常量值
 * @param objectBindings 复合绑定的 JSON Pointer 到 {@link FieldBinding} 映射；
 * 简单绑定时为 null
 * @since 0.1.0
 */
public record ArgumentBinding(
        int position,
        String name,
        String protocolType,
        ArgumentSource source,
        String sourcePath,
        String converter,
        Object constantValue,
        Map<String, FieldBinding> objectBindings
) {

    /**
     * 紧凑构造器，执行防御性拷贝与基础校验。
     *
     * @param position 参数位置
     * @param name 参数名
     * @param protocolType 协议类型名字符串
     * @param source 简单绑定的值来源
     * @param sourcePath 简单绑定的来源路径
     * @param converter 可选转换器名
     * @param constantValue 常量值
     * @param objectBindings 复合绑定的映射
     */
    public ArgumentBinding {
        java.util.Objects.requireNonNull(name, "name must not be null");
        java.util.Objects.requireNonNull(protocolType, "protocolType must not be null");
        if (objectBindings != null) {
            objectBindings = Map.copyOf(objectBindings);
        }
    }

    /**
     * 返回该参数是否使用复合绑定。
     *
     * @return {@code objectBindings} 非 null 且非空时为 {@code true}
     */
    public boolean isComposite() {
        return objectBindings != null && !objectBindings.isEmpty();
    }
}
