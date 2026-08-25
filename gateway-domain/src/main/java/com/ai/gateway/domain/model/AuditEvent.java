package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * 仅追加的审计事件，记录网关中终态或重要的状态迁移。
 *
 * <p>规定所有终态都必须被审计，包括：</p>
 *
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
 * <p>审计表采用仅追加权限，并通过 Outbox 导出到独立的 SIEM 或不可变存储。由于数据库
 * 管理员仍具备修改数据库的能力，仅凭业务表无法声称防篡改保证。</p>
 *
 * <p>运行时必须在最小身份核验之后、检索或 LLM 调用之前持久化一条 {@code REQUEST_ACCEPTED}
 * 事件；若持久化失败，网关拒绝继续处理。在调用 Provider 之前必须持久化 {@code STARTED}
 * 事件；调用之后，在将数据返回客户端之前必须持久化终态。</p>
 *
 * <p>敏感参数仅以脱敏摘要或不可逆哈希记录。追踪属性只记录能力 ID、版本、快照、稳定错误码
 * 与耗时——不包含敏感参数。</p>
 *
 * @param eventId 唯一事件标识
 * @param eventType 事件类型（如 "REQUEST_ACCEPTED"、"STARTED"、"SUCCEEDED"）
 * @param timestamp 事件时间戳
 * @param subjectDigest 调用方主体身份的摘要
 * @param orgId 组织上下文
 * @param requestId 请求标识
 * @param operationId 操作标识；只读场景为 null
 * @param capabilityId 能力标识；不适用时为 null
 * @param capabilityVersion 能力版本；不适用时为 null
 * @param manifestDigest 清单的 SHA-256 摘要；不适用时为 null
 * @param snapshotVersion 目录快照版本
 * @param policyDecisionId 鉴权策略决策 ID
 * @param modelPromptVersion 用于路由的模型/提示词版本
 * @param resultCode 稳定结果码（如某个 {@link ErrorCode} 名称）
 * @param durationMs 事件耗时（毫秒）
 * @param detailsJson 受控诊断摘要（JSON 形式）；绝不包含堆栈、内部地址或敏感参数
 * @since 0.1.0
 */
public record AuditEvent(
        String eventId,
        String eventType,
        Instant timestamp,
        String subjectDigest,
        long orgId,
        String requestId,
        String operationId,
        String capabilityId,
        String capabilityVersion,
        String manifestDigest,
        long snapshotVersion,
        String policyDecisionId,
        String modelPromptVersion,
        String resultCode,
        long durationMs,
        String detailsJson
) {

    /**
     * 紧凑构造器，对必填字段执行 null 检查。
     *
     * @param eventId 事件 ID
     * @param eventType 事件类型
     * @param timestamp 时间戳
     * @param subjectDigest 主体摘要
     * @param orgId 组织 ID
     * @param requestId 请求 ID
     * @param operationId 操作 ID
     * @param capabilityId 能力 ID
     * @param capabilityVersion 能力版本
     * @param manifestDigest 清单摘要
     * @param snapshotVersion 快照版本
     * @param policyDecisionId 策略决策 ID
     * @param modelPromptVersion 模型提示词版本
     * @param resultCode 结果码
     * @param durationMs 耗时（毫秒）
     * @param detailsJson 明细 JSON
     */
    public AuditEvent {
        java.util.Objects.requireNonNull(eventId, "eventId must not be null");
        java.util.Objects.requireNonNull(eventType, "eventType must not be null");
        java.util.Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}
