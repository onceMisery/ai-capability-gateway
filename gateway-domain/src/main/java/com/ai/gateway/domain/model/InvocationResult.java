package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * {@code InvocationAdapter} 返回的协议无关调用结果。
 *
 * <p>中性结果仅包含 JSON 兼容数据、协议状态、稳定错误码、错误消息与调用元数据。
 * 不包含原始协议对象、堆栈、内部地址、接口类名或敏感参数。</p>
 *
 * <p>定义结果处理顺序：</p>
 * <ol>
 * <li>适配器将协议结果转换为 JSON 兼容树。</li>
 * <li>检查响应大小、深度、集合长度与处理耗时。</li>
 * <li>信封规则判定业务成功并提取数据。</li>
 * <li>投影白名单构建公开结果。</li>
 * <li>应用字段脱敏。</li>
 * <li>校验公开出参 Schema。</li>
 * <li>生成结构化结果。</li>
 * <li>可基于脱敏后的结构化结果生成可选的自然语言摘要。</li>
 * </ol>
 *
 * <p>结构化结果是权威结果。即使自然语言摘要生成失败，也必须返回结构化结果。</p>
 *
 * @param jsonData JSON 兼容的结果数据；出错时为 null
 * @param protocolStatus 协议级状态字符串（如 "OK"、"TIMEOUT"）
 * @param errorCode 稳定错误码；成功时为 null
 * @param errorMessage 受控错误消息；成功时为 null
 * @param metadata 调用元数据（如耗时、Provider 节点）
 * @since 0.1.0
 */
public record InvocationResult(
        Object jsonData,
        String protocolStatus,
        ErrorCode errorCode,
        String errorMessage,
        Map<String, String> metadata
) {

    /**
     * 紧凑构造器，执行防御性拷贝。
     *
     * @param jsonData 结果数据
     * @param protocolStatus 协议状态
     * @param errorCode 错误码
     * @param errorMessage 错误消息
     * @param metadata 元数据映射
     */
    public InvocationResult {
        java.util.Objects.requireNonNull(protocolStatus, "protocolStatus must not be null");
        if (metadata != null) {
            metadata = Map.copyOf(metadata);
        }
    }
}
