package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * 遵循 {@code domain:resource:action} 三段式约定的权限词。
 *
 * <p>权限词通过管理控制台管理，并用于能力级鉴权。权限名中禁止通配符（{@code *}）；
 * 通配符保留给 Principal 内建的超级权限使用。</p>
 *
 * @param name 权限词（如 "order:detail:read"）
 * @param description 可读描述
 * @param createdAt 创建时间戳
 * @since 0.1.0
 */
public record Permission(
        String name,
        String description,
        Instant createdAt
) {

    /**
     * 合法权限名的正则：由冒号分隔的三个小写段。
     */
    public static final String NAME_PATTERN = "^[a-z][a-z0-9]*(:[a-z][a-z0-9]*){2}$";

    /**
     * 紧凑构造器，执行 null 检查与名称校验。
     */
    public Permission {
        java.util.Objects.requireNonNull(name, "name must not be null");
        java.util.Objects.requireNonNull(description, "description must not be null");
        java.util.Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "Permission name must match pattern " + NAME_PATTERN + ": " + name);
        }
        if (name.contains("*")) {
            throw new IllegalArgumentException("Permission name must not contain wildcard: " + name);
        }
    }
}
