package com.ai.gateway.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 能力的完整出参契约，定义协议响应在返回调用方之前如何被解包、投影、脱敏与校验。
 *
 * <p>定义响应处理流水线：</p>
 * <ol>
 * <li>适配器将协议结果转换为 JSON 兼容树。</li>
 * <li>若 {@code mode} 为 {@link OutputMode#ENVELOPE}，使用信封配置判定业务成功并
 * 提取数据载荷。</li>
 * <li>若 {@code mode} 为 {@link OutputMode#DIRECT}，根节点即为数据。</li>
 * <li>{@code projections} 白名单将 Provider 数据映射到公开输出。未映射字段不会离开网关。</li>
 * <li>{@code redactions} 规则应用字段级掩码、哈希或删除。</li>
 * <li>{@code publicSchema} 校验最终公开输出。</li>
 * <li>{@code maxBytes} 限制强制响应大小约束。</li>
 * </ol>
 *
 * <p>路径未找到、类型不匹配、响应超限或业务成功无法判定，都必须视为协议错误
 * —— 原始对象绝不能直接返回给用户或模型。</p>
 *
 * @param mode 输出模式（信封或直接）
 * @param envelope 信封配置；ENVELOPE 模式必需，DIRECT 模式忽略
 * @param projections JSON Pointer 投影白名单；若为空，则整个提取数据必须匹配 publicSchema
 * @param publicSchema 校验公开输出的 JSON Schema 2020-12
 * @param redactions 字段级脱敏规则
 * @param maxBytes 响应最大字节数
 * @since 0.1.0
 */
public record OutputContract(
        OutputMode mode,
        EnvelopeConfig envelope,
        List<ProjectionMapping> projections,
        Map<String, Object> publicSchema,
        List<RedactionRule> redactions,
        int maxBytes
) {
    /**
     * 紧凑构造器，执行防御性拷贝。
     *
     * @param mode 输出模式
     * @param envelope 信封配置
     * @param projections 投影映射
     * @param publicSchema 公开 JSON Schema
     * @param redactions 脱敏规则
     * @param maxBytes 响应最大字节数
     */
    public OutputContract {
        java.util.Objects.requireNonNull(mode, "mode must not be null");
        java.util.Objects.requireNonNull(projections, "projections must not be null");
        java.util.Objects.requireNonNull(publicSchema, "publicSchema must not be null");
        java.util.Objects.requireNonNull(redactions, "redactions must not be null");
        projections = List.copyOf(projections);
        redactions = List.copyOf(redactions);
        publicSchema = Map.copyOf(publicSchema);
    }
}
