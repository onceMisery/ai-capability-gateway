package com.ai.gateway.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * 用于列表视图的能力清单轻量摘要。
 *
 * <p>将清单元数据与生命周期状态、校验状态及快照版本历史聚合在一起。不包含敏感的
 * 调用细节。</p>
 *
 * @param capabilityId 全局稳定的能力标识
 * @param version 语义化版本字符串
 * @param displayName 面向用户的能力名称
 * @param description 单一业务动作描述
 * @param risk 风险等级
 * @param lifecycle 当前生命周期状态
 * @param tags 受控标签
 * @param ownerTeam 责任团队名称
 * @param ownerContact 责任团队联系方式
 * @param sha256Digest 内容 SHA-256 摘要
 * @param updatedAt 最后更新时间戳
 * @param snapshotVersions 包含该能力的快照版本列表
 * @since 0.1.0
 */
public record CapabilitySummary(
        String capabilityId,
        String version,
        String displayName,
        String description,
        RiskLevel risk,
        CapabilityLifecycle lifecycle,
        List<String> tags,
        String ownerTeam,
        String ownerContact,
        String sha256Digest,
        Instant updatedAt,
        List<Long> snapshotVersions
) {

    /**
     * 紧凑构造器，执行防御性拷贝。
     */
    public CapabilitySummary {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(version, "version must not be null");
        java.util.Objects.requireNonNull(displayName, "displayName must not be null");
        java.util.Objects.requireNonNull(description, "description must not be null");
        java.util.Objects.requireNonNull(risk, "risk must not be null");
        java.util.Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        java.util.Objects.requireNonNull(ownerTeam, "ownerTeam must not be null");
        java.util.Objects.requireNonNull(ownerContact, "ownerContact must not be null");
        java.util.Objects.requireNonNull(sha256Digest, "sha256Digest must not be null");
        java.util.Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        tags = tags == null ? List.of() : List.copyOf(tags);
        snapshotVersions = snapshotVersions == null ? List.of() : List.copyOf(snapshotVersions);
    }
}
