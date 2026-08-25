package com.ai.gateway.domain.model;

import java.util.List;

/**
 * 传递给 {@code InvocationAdapter} 的协议无关调用请求。
 *
 * <p>中性请求包含能力标识、截止预算、幂等键、追踪上下文，以及完整绑定、按位置排序的
 * 协议参数。适配器不得执行自然语言路由、用户鉴权或能力状态变更。</p>
 *
 * <p>{@code manifestDigest} 让适配器与审计层能够校验本次调用针对的是快照中发布的确切
 * 清单内容。{@code boundArguments} 是完全解析、非模型注入的协议参数——它们仅存在于执行
 * 内存中，不得明文记录到日志。</p>
 *
 * @param capabilityId 能力标识
 * @param capabilityVersion 语义化版本
 * @param manifestDigest 被调用清单内容的 SHA-256 摘要
 * @param deadlineBudget 本次调用的剩余截止预算
 * @param idempotencyKey 服务端生成的幂等键；只读请求可为 null
 * @param systemContext 平台执行上下文（trace、locale 等）
 * @param boundArguments 按序完整绑定的协议参数
 * @since 0.1.0
 */
public record InvocationRequest(
        String capabilityId,
        String capabilityVersion,
        String manifestDigest,
        DeadlineBudget deadlineBudget,
        String idempotencyKey,
        SystemContext systemContext,
        List<Object> boundArguments
) {

    /**
     * 紧凑构造器，执行防御性拷贝与 null 检查。
     *
     * @param capabilityId 能力 ID
     * @param capabilityVersion 能力版本
     * @param manifestDigest 清单摘要
     * @param deadlineBudget 截止预算
     * @param idempotencyKey 幂等键
     * @param systemContext 系统上下文
     * @param boundArguments 绑定参数
     */
    public InvocationRequest {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(capabilityVersion, "capabilityVersion must not be null");
        java.util.Objects.requireNonNull(manifestDigest, "manifestDigest must not be null");
        java.util.Objects.requireNonNull(deadlineBudget, "deadlineBudget must not be null");
        java.util.Objects.requireNonNull(systemContext, "systemContext must not be null");
        java.util.Objects.requireNonNull(boundArguments, "boundArguments must not be null");
        boundArguments = List.copyOf(boundArguments);
    }
}
