package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * 写操作两阶段协议 Prepare 阶段持久化的不可变操作记录。
 *
 * <p>规定 Prepare 阶段必须持久化一条至少包含以下内容的不可变操作记录：</p>
 *
 * <pre>
 * operationId
 * state = PREPARED
 * principalDigest + orgId
 * capabilityId + version + manifestDigest
 * snapshotVersion
 * encryptedArguments
 * argumentsDigest
 * idempotencyKey
 * policyDecisionId
 * confirmationSummary
 * expiresAt
 * originPlane
 * </pre>
 *
 * <p>{@code originPlane} 记录发起 Prepare 的入口平面。Confirm/Cancel 是独立请求，
 * 无法从其调用上下文推断写操作最初来自哪个平面；不落库就只能在终态审计里省略平面标签，
 * 或者一律按结构化直调归属——后者会把 MCP、A2A 发起的写操作错误计入结构化直调，
 * 直接污染成本归因。因此发起平面与冻结参数一样，属于 Prepare 阶段必须固化的事实。</p>
 *
 * <p>签发给用户的确认令牌必须是一次性、短时效的，并绑定到 {@code operationId}、
 * {@code principalDigest}、{@code orgId}、{@code argumentsDigest} 与服务端签名。
 * {@code encryptedArguments} 字段以静态加密方式存储绑定参数；{@code argumentsDigest}
 * 允许在不解密的情况下校验完整性。</p>
 *
 * <p>{@code version} 字段支持乐观并发控制：Confirm 阶段对 version 执行条件数据库更新，
 * 以原子方式认领执行权。</p>
 *
 * @param operationId 唯一操作标识
 * @param state 当前操作状态
 * @param principalDigest Prepare 阶段 Principal 的摘要
 * @param orgId 组织上下文
 * @param capabilityId 能力标识
 * @param capabilityVersion 能力语义化版本
 * @param manifestDigest 被调用清单的 SHA-256 摘要
 * @param snapshotVersion Prepare 时刻的目录快照版本
 * @param encryptedArguments 加密后的绑定参数
 * @param argumentsDigest 绑定参数的摘要
 * @param idempotencyKey 服务端生成的幂等键
 * @param policyDecisionId 鉴权策略决策 ID
 * @param confirmationSummary 展示给用户的确认摘要
 * @param expiresAt 操作过期时间
 * @param version 乐观并发版本
 * @param originPlane 发起 Prepare 的入口平面；从持久层读回缺失值时为
 *        {@link AuditPlane#UNKNOWN}
 * @since 0.1.0
 */
public record OperationRecord(
        String operationId,
        OperationState state,
        String principalDigest,
        long orgId,
        String capabilityId,
        String capabilityVersion,
        String manifestDigest,
        long snapshotVersion,
        String encryptedArguments,
        String argumentsDigest,
        String idempotencyKey,
        String policyDecisionId,
        ConfirmationSummary confirmationSummary,
        Instant expiresAt,
        long version,
        AuditPlane originPlane
) {

    /**
     * 紧凑构造器，对必填字段执行 null 检查。
     *
     * @param operationId 操作 ID
     * @param state 操作状态
     * @param principalDigest 主体摘要
     * @param orgId 组织 ID
     * @param capabilityId 能力 ID
     * @param capabilityVersion 能力版本
     * @param manifestDigest 清单摘要
     * @param snapshotVersion 快照版本
     * @param encryptedArguments 加密参数
     * @param argumentsDigest 参数摘要
     * @param idempotencyKey 幂等键
     * @param policyDecisionId 策略决策 ID
     * @param confirmationSummary 确认摘要
     * @param expiresAt 过期时间
     * @param version 乐观版本
     * @param originPlane 发起平面；{@code null} 归一化为 {@link AuditPlane#UNKNOWN}
     */
    public OperationRecord {
        java.util.Objects.requireNonNull(operationId, "operationId must not be null");
        java.util.Objects.requireNonNull(state, "state must not be null");
        java.util.Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(capabilityVersion, "capabilityVersion must not be null");
        java.util.Objects.requireNonNull(manifestDigest, "manifestDigest must not be null");
        java.util.Objects.requireNonNull(encryptedArguments, "encryptedArguments must not be null");
        java.util.Objects.requireNonNull(argumentsDigest, "argumentsDigest must not be null");
        java.util.Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        // 平面缺失不是非法状态：迁移之前入库的行没有这一列。归一化收在此处一处，
        // 使记录读回后 originPlane() 永不为 null，下游无需重复判空。
        if (originPlane == null) {
            originPlane = AuditPlane.UNKNOWN;
        }
    }

    /**
     * 兼容既有调用方的构造器：未声明发起平面时记为 {@link AuditPlane#UNKNOWN}。
     *
     * <p>刻意不默认成 {@link AuditPlane#STRUCTURED}——见类型注释中关于成本归因的说明。</p>
     */
    public OperationRecord(
            String operationId,
            OperationState state,
            String principalDigest,
            long orgId,
            String capabilityId,
            String capabilityVersion,
            String manifestDigest,
            long snapshotVersion,
            String encryptedArguments,
            String argumentsDigest,
            String idempotencyKey,
            String policyDecisionId,
            ConfirmationSummary confirmationSummary,
            Instant expiresAt,
            long version) {
        this(operationId, state, principalDigest, orgId, capabilityId, capabilityVersion,
                manifestDigest, snapshotVersion, encryptedArguments, argumentsDigest,
                idempotencyKey, policyDecisionId, confirmationSummary, expiresAt, version,
                AuditPlane.UNKNOWN);
    }
}
