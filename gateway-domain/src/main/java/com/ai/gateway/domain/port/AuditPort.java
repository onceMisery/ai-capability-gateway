package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.ExecutionAuditContext;

/**
 * 以 Fail Closed（失效关闭）语义持久化审计事件的端口。
 *
 * <p>规定所有终态都必须被审计，包括：</p>
 * <ul>
 * <li>鉴权失败。</li>
 * <li>无匹配与澄清事件。</li>
 * <li>权限拒绝。</li>
 * <li>模型输出无效。</li>
 * <li>参数校验失败。</li>
 * <li>调用成功、业务失败、协议错误与超时。</li>
 * <li>写操作的 Prepare、Confirm、Cancel、Expire、Unknown 与恢复事件。</li>
 * <li>清单的导入、确认、发布、下线（suspend）与回滚事件。</li>
 * </ul>
 *
 * <p>运行时必须在最小身份核验之后、检索或 LLM 调用之前持久化一条 {@code REQUEST_ACCEPTED}
 * 事件。若持久化失败，网关拒绝继续处理（Fail Closed）。在调用 Provider 之前必须持久化
 * {@code STARTED} 事件；调用之后必须在向客户端返回数据之前持久化终态。终态持久化失败
 * 时不得返回 Provider 数据。</p>
 *
 * <p>审计表采用仅追加权限，并通过 Outbox 导出到独立的 SIEM 或不可变存储。由于数据库
 * 管理员仍具备修改数据库的能力，仅凭业务表无法声称防篡改保证。</p>
 *
 * <p>敏感参数仅以脱敏摘要或不可逆哈希记录。追踪属性只记录能力 ID、版本、快照、稳定
 * 错误码与耗时——不包含敏感参数。</p>
 *
 * <p>实现此端口的适配器将审计事件持久化到 PostgreSQL，并采用微批优化。该端口是纯粹的
 * 领域抽象，不依赖任何框架。</p>
 *
 * @see AuditEvent
 * @since 0.1.0
 */
public interface AuditPort {

    /**
     * 记录一条 {@code REQUEST_ACCEPTED} 审计事件。
     *
     * <p>规定：在最小身份核验之后，运行时必须在检索或 LLM 调用之前持久化该事件。若
     * 持久化失败，网关拒绝继续处理（Fail Closed）。</p>
     *
     * @param requestId 唯一请求标识
     * @param subjectDigest 调用方主体身份的摘要
     * @param orgId 组织上下文
     */
    void recordAccepted(String requestId, String subjectDigest, long orgId);

    /**
     * 在调用 Provider 之前记录一条 {@code STARTED} 审计事件。
     *
     * <p>规定：在调用 Provider 之前必须持久化 {@code STARTED} 事件。调用之后必须在向
     * 客户端返回数据之前持久化终态。终态持久化失败不得返回 Provider 数据。</p>
     *
     * @param context 执行审计上下文，包含身份、租户、能力与固定快照
     */
    void recordStarted(ExecutionAuditContext context);

    /**
     * 在 Provider 调用完成后记录一条终态审计事件。
     *
     * <p>规定：终态必须在向客户端返回数据之前持久化。敏感参数仅以脱敏摘要或不可逆
     * 哈希记录。</p>
     *
     * @param context 执行审计上下文，必须与 STARTED 阶段使用同一份上下文
     * @param resultCode 稳定结果码（如某个 {@link com.ai.gateway.domain.model.ErrorCode} 名称）
     * @param durationMs 调用耗时（毫秒）
     * @param detailsJson 受控诊断摘要（JSON 形式）；绝不包含堆栈、内部地址或敏感参数
     */
    void recordTerminal(ExecutionAuditContext context, String resultCode,
                        long durationMs, String detailsJson);

    /**
     * 记录一条自定义审计事件。
     *
     * <p>用于上述便捷方法未覆盖的事件，如鉴权失败、无匹配、澄清、权限拒绝、清单生命周期
     * 变更与写操作状态迁移。</p>
     *
     * @param event 待持久化的审计事件；永不为 {@code null}
     */
    void recordEvent(AuditEvent event);
}
