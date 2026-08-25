package com.ai.gateway.domain.model;

/**
 * 需要显式授权决策的一组特权控制面（管理）操作。
 *
 * <p>这些操作会变更能力目录或其生命周期，因此受
 * {@link com.ai.gateway.domain.port.AuthorizationPort#authorizeAdmin(Principal,
 * AdminAction)} 门控。该枚举是封闭的：新增管理操作必须有意在此添加，从而保持授权覆盖
 * 的明确性。</p>
 *
 * @since 0.1.0
 */
public enum AdminAction {

    /** 读取控制面状态、监控数据、ACL 或配置。 */
    READ,

    /** 将能力清单导入目录（10 步校验）。 */
    IMPORT,

    /** 审批已导入的清单，推进其生命周期状态。 */
    APPROVE,

    /** 向某环境发布目录快照。 */
    PUBLISH,

    /** 将某环境回滚到历史快照版本。 */
    ROLLBACK,

    /** 通过应急下线（suspend）暂停某能力。 */
    SUSPEND,

    /** 管理 ACL 条目、角色与权限。 */
    MANAGE_ACL,

    /** 修改网关配置或限流规则。 */
    CONFIGURE,

    /**
     * 运行能力目录诊断（dry-run）：复现「授权过滤 → BM25 → 投影 → LLM 受限选择」链路
     * 并输出归因，不产生任何业务副作用。
     *
     * <p>之所以独立于 {@link #READ}：该操作会以指定角色视角观察目录、并真实消耗模型额度，
     * 因此需要可单独授予/收回的权限位，而不是与普通读操作绑死。</p>
     */
    DIAGNOSE
}
