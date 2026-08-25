package com.ai.gateway.domain.model;

/**
 * 远端 Agent（A2A peer）的信任分级。
 *
 * <p>它回答的是同一个问题的三种答案：<b>这个 peer 的执行语义边界在哪里</b>。分级只由网关侧的
 * 指纹注册表决定，不接受 peer 自声明——A2A 消息体里的任何字段都是不可信输入。</p>
 *
 * <ul>
 * <li>{@code UNTRUSTED} — 未认证或认证失败的 peer。任何能力都不投影，扩展卡等价于公开卡。</li>
 * <li>{@code READ_ONLY} — 已认证但未命中信任注册表的 peer。这是<b>默认档位</b>，
 * 仅投影 {@link RiskLevel#READ_ONLY} 能力。</li>
 * <li>{@code TRUSTED_CONFIRMATION} — 已认证且具备独立确认通道的 peer。额外允许
 * {@link RiskLevel#WRITE_LOW}，写操作仍然走完整的两阶段 Prepare/Confirm 协议，
 * 分级只决定「是否让它看见」，不决定「是否可以跳过确认」。</li>
 * </ul>
 *
 * <p>{@link RiskLevel#WRITE_HIGH} 在任何分级下都不投影：它默认禁用，须经独立安全评审与
 * 双人审批，把它放进任何 Agent 侧可见面都等于绕过那道评审。</p>
 *
 * @see AgentIdentity
 * @since 0.1.0
 */
public enum TrustTier {

    /** 未认证/认证失败：零投影。 */
    UNTRUSTED,

    /** 已认证但未注册：仅只读投影，未命中注册表时的恒定档位。 */
    READ_ONLY,

    /** 已认证且具备独立确认通道：只读 + 低风险写投影。 */
    TRUSTED_CONFIRMATION;

    /**
     * 判定该风险等级的能力是否可以进入本分级 peer 的可见面。
     *
     * <p>判定集中在枚举内部，是为了让「新增一个分级」只需修改本方法而不必改动任何投影逻辑
     * （开闭原则）；同时保证 AgentCard 投影、入站准入与出站委托三处使用同一份判定，
     * 不会出现「卡片里看得见、执行时被拒」这类可被用来探测策略的差异。</p>
     *
     * @param risk 能力的风险等级，{@code null} 视为不允许（失效关闭）
     * @return 允许投影时返回 {@code true}
     */
    public boolean allowsProjection(RiskLevel risk) {
        if (risk == null) {
            return false;
        }
        return switch (this) {
            case UNTRUSTED -> false;
            case READ_ONLY -> risk == RiskLevel.READ_ONLY;
            case TRUSTED_CONFIRMATION ->
                    risk == RiskLevel.READ_ONLY || risk == RiskLevel.WRITE_LOW;
        };
    }
}
