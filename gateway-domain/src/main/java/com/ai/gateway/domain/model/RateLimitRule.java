package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * A rate-limit rule (flow or degrade) for Sentinel-based traffic control.
 *
 * <p>Used by the admin console to view and manage Sentinel rules at runtime.
 * Rules are held in memory and reset to hardcoded baselines on restart.</p>
 *
 * @param type the rule type ("flow" or "degrade")
 * @param resource the Sentinel resource name (e.g., "gateway:capability:order.detail.query")
 * @param properties the rule-specific properties (grade, count, strategy, etc.)
 * @since 0.1.0
 */
public record RateLimitRule(
        String type,
        String resource,
        Map<String, Object> properties
) {

    /**
     * Compact constructor performing null checks and defensive copying.
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
