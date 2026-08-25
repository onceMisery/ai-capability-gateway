package com.ai.gateway.domain.model;

import java.util.Objects;

/**
 * 一次 A2A 任务在网关侧的执行上下文。
 *
 * <p>{@code rootRequestId} 是整条委托链的业务面标识：它由链首生成，之后逐跳透传而<b>不重新生成</b>，
 * 使跨 Agent 的任务链在审计里可以被串成一条事实记录。它与分布式 traceId 的关系是互补的——
 * traceId 解释「调用怎么发生」，{@code rootRequestId} 解释「这是谁的哪一次业务请求」。</p>
 *
 * <p>{@code delegationDepth} 是 A2A 协议本身没有、必须由策略执行点补上的防护。缺了它，
 * Agent 之间的环路会把一次用户请求放大成不可控的调用扇出，而每一跳看起来都完全合法。</p>
 *
 * @param taskId           A2A Task 标识
 * @param contextId        A2A 会话上下文标识，同一会话内多个 Task 共享
 * @param rootRequestId    贯穿整条委托链的业务面标识，逐跳透传
 * @param delegationDepth  已经历的委托跳数，链首为 {@code 0}
 * @since 0.1.0
 */
public record A2aTaskContext(
        String taskId,
        String contextId,
        String rootRequestId,
        int delegationDepth
) {

    /**
     * 紧凑构造器。
     *
     * @param taskId          任务标识，不能为空
     * @param contextId       会话上下文标识，不能为空
     * @param rootRequestId   委托链标识，不能为空
     * @param delegationDepth 委托跳数，不能为负
     */
    public A2aTaskContext {
        requireText(taskId, "taskId");
        requireText(contextId, "contextId");
        requireText(rootRequestId, "rootRequestId");
        if (delegationDepth < 0) {
            throw new IllegalArgumentException("delegationDepth must not be negative");
        }
    }

    /**
     * 判定当前跳数是否仍在允许范围内。
     *
     * <p>判定放在领域模型里，是为了让入站准入与出站委托两处使用同一份边界语义：
     * 若各自实现，就会出现「入站放行、出站再放大一跳」的缺口。</p>
     *
     * @param maxDelegationDepth 允许的最大跳数（配置项 {@code gateway.a2a.max-delegation-depth}）
     * @return 未超限时返回 {@code true}；{@code maxDelegationDepth} 非正时恒为 {@code false}
     */
    public boolean withinDepth(int maxDelegationDepth) {
        return maxDelegationDepth > 0 && delegationDepth < maxDelegationDepth;
    }

    /**
     * 派生下一跳上下文：跳数 +1，其余标识原样保持。
     *
     * <p>{@code rootRequestId} 必须原样透传——一旦某一跳重新生成它，委托链就在审计里被截断，
     * 而环路检测也随之失效。</p>
     *
     * @return 下一跳的执行上下文
     */
    public A2aTaskContext descend() {
        return new A2aTaskContext(taskId, contextId, rootRequestId, delegationDepth + 1);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
