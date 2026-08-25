package com.ai.gateway.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * 为鉴权决策分组一组权限的具名角色。
 *
 * <p>角色通过管理控制台管理并持久化到 PostgreSQL。每个角色携带一组权限词，决定该角色
 * 可访问哪些能力与后台管理操作。</p>
 *
 * @param name 唯一角色名
 * @param description 可读描述
 * @param permissions 分配给该角色的权限词
 * @param createdAt 创建时间戳
 * @param updatedAt 最后更新时间戳
 * @since 0.1.0
 */
public record Role(
        String name,
        String description,
        List<String> permissions,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 紧凑构造器，执行防御性拷贝。
     */
    public Role {
        java.util.Objects.requireNonNull(name, "name must not be null");
        java.util.Objects.requireNonNull(description, "description must not be null");
        java.util.Objects.requireNonNull(createdAt, "createdAt must not be null");
        java.util.Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
