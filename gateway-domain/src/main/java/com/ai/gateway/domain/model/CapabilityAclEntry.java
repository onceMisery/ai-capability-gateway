package com.ai.gateway.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * 访问控制列表条目，将一个能力映射到允许调用它的角色集合。
 *
 * <p>供管理控制台配置能力级鉴权。每个条目精确作用于一个能力 ID 与版本。</p>
 *
 * @param capabilityId 能力标识
 * @param capabilityVersion 能力版本
 * @param allowedRoles 允许执行该能力的角色集合
 * @param requiredPermissions 清单 {@code spec.authorization.permissions} 中声明的权限
 * @param updatedAt 最后更新时间戳
 * @param updatedBy 最后更新该条目的身份
 * @since 0.1.0
 */
public record CapabilityAclEntry(
        String capabilityId,
        String capabilityVersion,
        List<String> allowedRoles,
        List<String> requiredPermissions,
        Instant updatedAt,
        String updatedBy
) {

    /**
     * 紧凑构造器，执行防御性拷贝。
     */
    public CapabilityAclEntry {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(capabilityVersion, "capabilityVersion must not be null");
        java.util.Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        java.util.Objects.requireNonNull(updatedBy, "updatedBy must not be null");
        allowedRoles = allowedRoles == null ? List.of() : List.copyOf(allowedRoles);
        requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);
    }
}
