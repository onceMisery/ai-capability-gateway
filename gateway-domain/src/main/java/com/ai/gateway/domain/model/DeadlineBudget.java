package com.ai.gateway.domain.model;

/**
 * 请求执行的不可变截止时间预算追踪器。
 *
 * <p>规定入口截止时间必须在各流水线阶段（鉴权、检索、LLM 路由、校验、Provider 调用、
 * 结果治理）间分配。任何下游超时不得超过调用时刻的剩余时间。</p>
 *
 * <p>每个消耗时间的阶段调用 {@link #spend(long)} 生成一个剩余时间减少的新
 * {@code DeadlineBudget}。原实例永不变更。{@code remainingMs <= 0} 的预算视为已过期。</p>
 *
 * <p>客户端断开不等于写操作失败；写操作必须通过状态查询 API 来确认结果。</p>
 *
 * @param totalDeadlineMs 原始总截止时间（毫秒）
 * @param remainingMs 截止前剩余毫秒数
 * @since 0.1.0
 */
public record DeadlineBudget(long totalDeadlineMs, long remainingMs) {

    /**
     * 返回一个新的预算，从剩余时间中扣除指定的毫秒数。
     *
     * @param ms 当前阶段消耗的毫秒数，必须为非负数
     * @return 剩余时间减少后的新 {@code DeadlineBudget}
     * @throws IllegalArgumentException 当 {@code ms} 为负数时
     */
    public DeadlineBudget spend(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("spent time must not be negative: " + ms);
        }
        long newRemaining = Math.max(0, remainingMs - ms);
        return new DeadlineBudget(totalDeadlineMs, newRemaining);
    }

    /**
     * 返回截止时间是否已耗尽。
     *
     * @return 当 {@code remainingMs <= 0} 时为 {@code true}
     */
    public boolean isExpired() {
        return remainingMs <= 0;
    }
}
