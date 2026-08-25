package com.ai.gateway.domain.model;

/**
 * 能力的风险等级，决定执行模式以及调用前必须施加的控制措施。
 *
 * <ul>
 * <li>{@code READ_ONLY} - 纯查询，对 Provider 状态无副作用。</li>
 * <li>{@code WRITE_LOW} - 写操作，需要第 13 节定义的两阶段 Prepare/Confirm 协议。</li>
 * <li>{@code WRITE_HIGH} - 高影响写操作，默认禁用，须经独立安全评审与双人审批后才启用。</li>
 * </ul>
 *
 * <p>风险等级与鉴权权限是正交的两个维度：{@code spec.authorization.permissions}
 * 决定主体<em>能否</em>调用该能力，而 {@code spec.risk} 决定调用被<em>多严格地</em>管控。</p>
 *
 * @see CapabilityManifest
 * @see ExecutionPlan
 * @see ConfirmationSummary
 *
 * @since 0.1.0
 */
public enum RiskLevel {
    /**
     * 只读查询。路由与参数校验通过后即可立即执行，无需进入两阶段协议。
     */
    READ_ONLY,

    /**
     * 低风险写操作。需要第 13 节的 Prepare/Confirm 协议，具备幂等、超时与不确定状态恢复能力。
     */
    WRITE_LOW,

    /**
     * 高风险写操作。初始版本默认禁用，仅在独立安全评审与双人审批机制到位后启用。
     */
    WRITE_HIGH
}
