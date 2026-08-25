package com.ai.gateway.domain.model;

import java.util.List;

/**
 * 信封模式响应解包的配置。
 *
 * <p>规定网关不能依赖业务的统一响应 JAR，而是使用结构化的 JSON Pointer 路径来判定业务
 * 成功与否并提取数据载荷：</p>
 *
 * <ul>
 * <li>{@code codePath} - 指向成功码字段的 JSON Pointer（如 {@code "/code"}）。</li>
 * <li>{@code successValues} - 表示成功的值集合（保留 JSON 标量类型）。注意：数字
 * {@code 0} 与字符串 {@code "0"} 不同，如需兼容两者都必须声明。</li>
 * <li>{@code dataPath} - 指向数据载荷字段的 JSON Pointer（如 {@code "/data"} 或
 * {@code "/value"}）。</li>
 * <li>{@code messagePath} - 指向可选 message 字段的 JSON Pointer（如 {@code "/message"}）。</li>
 * </ul>
 *
 * <p>平台标准信封配置示例：</p>
 * <pre>
 * codePath: /code
 * successValues: ["200"]
 * dataPath: /value
 * messagePath: /message
 * </pre>
 *
 * <p>适配器必须在信封判定、投影与公开 Schema 校验之前，递归剥离 Dubbo 泛化调用注入的
 * 协议元数据键（如 {@code class}）。</p>
 *
 * @param codePath 指向成功码字段的 JSON Pointer
 * @param successValues 表示业务成功的值列表
 * @param dataPath 指向数据载荷的 JSON Pointer
 * @param messagePath 指向 message 字段的 JSON Pointer；可为 null
 * @since 0.1.0
 */
public record EnvelopeConfig(
        String codePath,
        List<Object> successValues,
        String dataPath,
        String messagePath
) {
    /**
     * 紧凑构造器，执行防御性拷贝。
     *
     * @param codePath 指向成功码的 JSON Pointer
     * @param successValues 成功值列表
     * @param dataPath 指向数据的 JSON Pointer
     * @param messagePath 指向 message 的 JSON Pointer
     */
    public EnvelopeConfig {
        java.util.Objects.requireNonNull(codePath, "codePath must not be null");
        java.util.Objects.requireNonNull(successValues, "successValues must not be null");
        java.util.Objects.requireNonNull(dataPath, "dataPath must not be null");
        successValues = List.copyOf(successValues);
    }
}
