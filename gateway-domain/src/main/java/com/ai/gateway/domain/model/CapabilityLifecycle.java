package com.ai.gateway.domain.model;

/**
 * 能力清单的生命周期状态。
 *
 * <p>状态机如下：</p>
 * <pre>
 * DRAFT -&gt; VALIDATED -&gt; APPROVED -&gt; PUBLISHED -&gt; SUSPENDED -&gt; RETIRED
 *                 |                              |
 *                 +--&gt; REJECTED                  +--&gt; VALIDATED
 * </pre>
 * <p>精确规则：只有 VALIDATED 可迁移到终态 REJECTED；SUSPENDED（下线）版本在重新审批与
 * 发布前必须先重新校验。</p>
 *
 * <p>只有 {@code PUBLISHED} 的能力才能进入自然语言路由的候选集（第 9 节）。若在确认
 * 时发现能力处于 {@code SUSPENDED} 或 {@code RETIRED} 状态，必须拒绝写操作。</p>
 *
 * @since 0.1.0
 */
public enum CapabilityLifecycle {
    /**
     * 清单已导入且可编辑，尚未校验。
     */
    DRAFT,

    /**
     * 已通过设计文档中定义的全部结构、语义、安全与兼容性校验步骤。
     */
    VALIDATED,

    /**
     * 提交者已审阅确认摘要，且批量安全评审已通过。
     */
    APPROVED,

    /**
     * 已进入目标环境的活动快照。只有 PUBLISHED 能力参与路由。
     */
    PUBLISHED,

    /**
     * 暂时停止接收新请求。保留审计与恢复能力。恢复需重新校验并生成新快照。
     */
    SUSPENDED,

    /**
     * 永久从路由中移除。保留历史记录。
     */
    RETIRED,

    /**
     * 校验或审批被拒绝。该版本为终态；修正需导入新的清单版本。
     */
    REJECTED
}
