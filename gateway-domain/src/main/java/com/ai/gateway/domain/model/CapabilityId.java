package com.ai.gateway.domain.model;

import java.util.regex.Pattern;

/**
 * 全局稳定的能力标识。
 *
 * <p>规定 {@code metadata.id} 应采用 {@code domain.resource.action} 命名约定
 *（如 {@code order.detail.query}）。标识只能包含小写字母、数字、点（{@code .}）与
 * 连字符（{@code -}）。一旦发布，ID 不可重命名。</p>
 *
 * <p>这是一个值对象：两个 {@code value} 相同的 {@code CapabilityId} 实例视为相等。</p>
 *
 * @param value 能力标识字符串（如 {@code "order.detail.query"}）
 * @since 0.1.0
 */
public record CapabilityId(String value) {

    /**
     * 校验能力标识的正则：仅允许小写字母、数字、点与连字符。
     */
    private static final Pattern VALID_PATTERN =
            Pattern.compile("^[a-z0-9.\\-]+$");

    /**
     * 紧凑构造器，执行格式校验。
     *
     * @param value 能力标识字符串
     * @throws NullPointerException 当 {@code value} 为 null 时
     * @throws IllegalArgumentException 当 {@code value} 为空白或包含允许字符集之外的字符时
     */
    public CapabilityId {
        java.util.Objects.requireNonNull(value, "capabilityId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("capabilityId value must not be blank");
        }
        if (!VALID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "capabilityId value must contain only lowercase letters, digits, dots, and hyphens: " + value);
        }
    }
}
