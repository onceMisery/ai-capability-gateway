package com.ai.gateway.domain.model;

import java.util.Objects;

/**
 * 一次能力执行在各审计阶段共享的不可变上下文。
 *
 * <p>应用层是执行身份、租户、能力版本和目录快照的事实所有者。将这些字段作为一个整体
 * 传递给审计端口，可以避免长参数列表，也防止持久化适配器因缺少上下文而写入
 * {@code null} 或 {@code 0}。</p>
 *
 * @param requestId 请求标识
 * @param operationId 写操作标识；只读执行时为 {@code null}
 * @param subjectDigest 已认证主体的 SHA-256 摘要
 * @param orgId 已验证的组织标识
 * @param capabilityId 能力标识
 * @param capabilityVersion 能力版本
 * @param manifestDigest Manifest 摘要
 * @param snapshotVersion 执行时固定的目录快照版本
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public record ExecutionAuditContext(
        String requestId,
        String operationId,
        String subjectDigest,
        long orgId,
        String capabilityId,
        String capabilityVersion,
        String manifestDigest,
        long snapshotVersion
) {

    public ExecutionAuditContext {
        requireText(requestId, "requestId");
        requireText(subjectDigest, "subjectDigest");
        requireText(capabilityId, "capabilityId");
        requireText(capabilityVersion, "capabilityVersion");
        requireText(manifestDigest, "manifestDigest");
        if (snapshotVersion <= 0) {
            throw new IllegalArgumentException("snapshotVersion must be positive");
        }
    }

    /**
     * 根据只读执行计划和已认证主体创建审计上下文。
     */
    public static ExecutionAuditContext forExecution(
            String requestId, ExecutionPlan plan, Principal principal) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        return new ExecutionAuditContext(
                requestId,
                null,
                plan.principalDigest(),
                principal.orgId(),
                plan.capabilityId(),
                plan.capabilityVersion(),
                plan.manifestDigest(),
                plan.snapshotVersion());
    }

    /**
     * 根据冻结的写操作记录创建审计上下文。
     */
    public static ExecutionAuditContext forOperation(OperationRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        return new ExecutionAuditContext(
                record.operationId(),
                record.operationId(),
                record.principalDigest(),
                record.orgId(),
                record.capabilityId(),
                record.capabilityVersion(),
                record.manifestDigest(),
                record.snapshotVersion());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
