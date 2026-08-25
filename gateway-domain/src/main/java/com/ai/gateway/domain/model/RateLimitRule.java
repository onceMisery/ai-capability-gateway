package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * 基于 Sentinel 的限流规则（流控或降级）。
 *
 * <p>供管理控制台在运行时查看与管理 Sentinel 规则。规则保存在内存中，重启时重置为
 * 硬编码的基线值。</p>
 *
 * @param type 规则类型（"flow" 或 "degrade"）
 * @param resource Sentinel 资源名（如 "gateway:capability:order.detail.query"）
 * @param properties 规则特定属性（grade、count、strategy 等）
 * @since 0.1.0
 */
public record RateLimitRule(
        String type,
        String resource,
        Map<String, Object> properties
) {

    /**
     * 紧凑构造器，执行 null 检查与防御性拷贝。
     */
    public RateLimitRule {
        java.util.Objects.requireNonNull(type, "type must not be null");
        java.util.Objects.requireNonNull(resource, "resource must not be null");
        if (!type.equals("flow") && !type.equals("degrade")) {
            throw new IllegalArgumentException("type must be 'flow' or 'degrade': " + type);
        }
        if (!resource.startsWith("gateway:")) {
            throw new IllegalArgumentException("resource must start with 'gateway:': " + resource);
        }
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
