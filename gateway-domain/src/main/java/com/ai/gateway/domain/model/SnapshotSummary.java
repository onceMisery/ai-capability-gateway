package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * 供列表视图使用的目录快照轻量摘要。
 *
 * <p>供管理控制台展示快照历史，而无需加载完整快照内容。</p>
 *
 * @param snapshotVersion 单调递增的快照版本
 * @param environment 目标环境（如 "production"）
 * @param status 快照状态（ACTIVE 或 SUPERSEDED）
 * @param digest 快照内容的 SHA-256 摘要
 * @param capabilityCount 快照中能力数量
 * @param publishedAt 发布时间戳
 * @param publishedBy 发布该快照的身份
 * @since 0.1.0
 */
public record SnapshotSummary(
        long snapshotVersion,
        String environment,
        String status,
        String digest,
        int capabilityCount,
        Instant publishedAt,
        String publishedBy
) {

    /**
     * 紧凑构造器，执行 null 检查。
     */
    public SnapshotSummary {
        java.util.Objects.requireNonNull(environment, "environment must not be null");
        java.util.Objects.requireNonNull(status, "status must not be null");
        java.util.Objects.requireNonNull(digest, "digest must not be null");
        java.util.Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        java.util.Objects.requireNonNull(publishedBy, "publishedBy must not be null");
    }
}
