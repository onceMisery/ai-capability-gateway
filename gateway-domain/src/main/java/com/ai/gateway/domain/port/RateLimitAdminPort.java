package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.RateLimitRule;

/**
 * Port for runtime rate-limit rule administration.
 *
 * <p>Allows the admin console to create, update, and delete Sentinel
 * rate-limit rules at runtime. The port is a pure abstraction with no
 * framework dependencies.</p>
 *
 * @since 0.1.0
 */
public interface RateLimitAdminPort {

    /**
     * Adds or updates a rate-limit rule.
     *
     * @param rule the rule to add or update
     */
    void saveRule(RateLimitRule rule);

    /**
     * Deletes a rate-limit rule.
     *
     * @param type the rule type ("flow" or "degrade")
     * @param resource the Sentinel resource name
     */
    void deleteRule(String type, String resource);
}