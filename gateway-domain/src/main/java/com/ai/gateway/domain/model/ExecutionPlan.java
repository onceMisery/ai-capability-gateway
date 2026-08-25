package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * 模型路由成功后生成的不可变执行计划。
 *
 * <p>规定模型选定能力且网关完成参数校验后，会生成一个不可变的 ExecutionPlan，
 * 包含：</p>
 *
 * <pre>
 * executionId
 * principalDigest
 * snapshotVersion
 * capabilityId + capabilityVersion + manifestDigest
 * validatedModelArguments
 * resolvedProtocolArguments
 * policyDecisionId
 * risk
 * timeout/retry/idempotency 策略
 * </pre>
 *
 * <p>执行计划不得包含任何可被模型修改的协议配置。只读操作可立即执行；写操作必须进入
 * 两阶段协议（第 13 节）的 PREPARED 状态。</p>
 *
 * <p>{@code validatedModelArguments} 是通过 Schema 与业务校验的模型生成参数。
 * {@code resolvedProtocolArguments} 是已注入 PRINCIPAL、CONSTANT、SYSTEM 值的完整绑定参数
 * —— 它们仅存在于执行内存中，不得明文记录到日志。</p>
 *
 * @param executionId 唯一执行标识
 * @param principalDigest 执行主体 Principal 的摘要
 * @param snapshotVersion 路由时刻的目录快照版本
 * @param capabilityId 能力标识
 * @param capabilityVersion 能力语义化版本
 * @param manifestDigest 被调用清单的 SHA-256 摘要
 * @param validatedModelArguments 已校验的模型生成参数
 * @param resolvedProtocolArguments 完整绑定、按位置排序的参数
 * @param policyDecisionId 鉴权策略决策 ID
 * @param risk 能力风险等级
 * @param resiliencePolicy 超时、重试与并发策略
 * @since 0.1.0
 */
public record ExecutionPlan(
        String executionId,
        String principalDigest,
        long snapshotVersion,
        String capabilityId,
        String capabilityVersion,
        String manifestDigest,
        Map<String, Object> validatedModelArguments,
        java.util.List<Object> resolvedProtocolArguments,
        String policyDecisionId,
        RiskLevel risk,
        ResiliencePolicy resiliencePolicy
) {

    /**
     * 紧凑构造器，执行防御性拷贝与 null 检查。
     *
     * @param executionId 执行 ID
     * @param principalDigest 主体摘要
     * @param snapshotVersion 快照版本
     * @param capabilityId 能力 ID
     * @param capabilityVersion 能力版本
     * @param manifestDigest 清单摘要
     * @param validatedModelArguments 已校验的模型参数
     * @param resolvedProtocolArguments 已解析的协议参数
     * @param policyDecisionId 策略决策 ID
     * @param risk 风险等级
     * @param resiliencePolicy 韧性策略
     */
    public ExecutionPlan {
        java.util.Objects.requireNonNull(executionId, "executionId must not be null");
        java.util.Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(capabilityVersion, "capabilityVersion must not be null");
        java.util.Objects.requireNonNull(manifestDigest, "manifestDigest must not be null");
        java.util.Objects.requireNonNull(validatedModelArguments, "validatedModelArguments must not be null");
        java.util.Objects.requireNonNull(resolvedProtocolArguments, "resolvedProtocolArguments must not be null");
        java.util.Objects.requireNonNull(risk, "risk must not be null");
        java.util.Objects.requireNonNull(resiliencePolicy, "resiliencePolicy must not be null");
        validatedModelArguments = Map.copyOf(validatedModelArguments);
        resolvedProtocolArguments = java.util.List.copyOf(resolvedProtocolArguments);
    }
}
