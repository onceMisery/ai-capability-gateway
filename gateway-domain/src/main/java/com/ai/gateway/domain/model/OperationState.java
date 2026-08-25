package com.ai.gateway.domain.model;

/**
 * 写操作在两阶段 Prepare/Confirm 协议中的状态。
 *
 * <p>状态机如下：</p>
 * <pre>
 * PREPARED -&gt; EXECUTING -&gt; SUCCEEDED
 * | |----&gt; FAILED
 * | +----&gt; UNKNOWN -&gt; SUCCEEDED / FAILED / MANUAL_REVIEW
 * |-----------------&gt; EXPIRED
 * +-----------------&gt; CANCELLED
 * </pre>
 *
 * <p>{@code UNKNOWN} 表示请求可能已到达 Provider，但网关未收到确定性响应。网关不得
 * 自动重新执行该自然语言请求；相反，恢复任务应使用幂等键查询 Provider 状态。无法
 * 解决的案例进入 {@code MANUAL_REVIEW}。</p>
 *
 * @see OperationRecord
 * @since 0.1.0
 */
public enum OperationState {
    /**
     * Prepare 阶段已完成：参数已绑定、鉴权已检查，且不可变操作记录已持久化。
     * 已签发一个短时效确认令牌。
     */
    PREPARED,

    /**
     * Confirm 阶段已原子认领执行权，Provider 调用正在进行中。
     */
    EXECUTING,

    /**
     * Provider 调用返回了确定性的成功。
     */
    SUCCEEDED,

    /**
     * Provider 调用返回了确定性的失败。
     */
    FAILED,

    /**
     * 请求可能已到达 Provider，但网关未收到确定性响应。恢复任务必须使用幂等键查询
     * Provider 状态；禁止自动重新执行。
     */
    UNKNOWN,

    /**
     * 在 Confirm 调用之前确认令牌已过期。该操作无法再被确认。
     */
    EXPIRED,

    /**
     * 操作在执行前被显式取消。
     */
    CANCELLED,

    /**
     * 恢复任务无法自动判定最终状态。需要人工介入，并触发告警。
     */
    MANUAL_REVIEW
}
