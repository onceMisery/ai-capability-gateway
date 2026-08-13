package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.RateLimitRule;
import com.ai.gateway.domain.port.RateLimitAdminPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing Sentinel rate-limit rules at runtime.
 *
 * <p>Provides CRUD operations for Sentinel flow and degrade rules. Rules
 * are held in memory and reset to hardcoded baselines on restart. The admin
 * console uses this service to view and modify rate-limit thresholds.</p>
 *
 * <p>When Sentinel is not on the classpath, this service still maintains
 * the rule list in memory for admin console display purposes.</p>
 *
 * @since 0.1.0
 */
@Service
public class SentinelRuleAdminService implements RateLimitAdminPort {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleAdminService.class);

    private final Map<String, RateLimitRule> rules = new ConcurrentHashMap<>();

    /**
     * Constructs a new SentinelRuleAdminService with baseline rules.
     */
    public SentinelRuleAdminService() {
        initBaselineRules();
    }

    /**
     * Returns all current rate-limit rules.
     *
     * @return an unmodifiable list of rules; never {@code null}
     */
    public List<RateLimitRule> listRules() {
        return Collections.unmodifiableList(new ArrayList<>(rules.values()));
    }

    /**
     * Adds or updates a rate-limit rule.
     *
     * @param rule the rule to add or update
     */
    public void saveRule(RateLimitRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        String key = rule.type() + ":" + rule.resource();
        rules.put(key, rule);
        log.info("Rate-limit rule saved: type={}, resource={}", rule.type(), rule.resource());
    }

    /**
     * Deletes a rate-limit rule.
     *
     * @param type the rule type ("flow" or "degrade")
     * @param resource the Sentinel resource name
     */
    public void deleteRule(String type, String resource) {
        String key = type + ":" + resource;
        rules.remove(key);
        log.info("Rate-limit rule deleted: type={}, resource={}", type, resource);
    }

    /**
     * Resets all rules to the hardcoded baselines.
     */
    public void resetToBaseline() {
        rules.clear();
        initBaselineRules();
        log.info("Rate-limit rules reset to baseline");
    }

    private void initBaselineRules() {
        // Baseline flow rules
        rules.put("flow:gateway:capability:default", new RateLimitRule(
                "flow", "gateway:capability:default",
                Map.of("grade", 1, "count", 100.0, "strategy", 0, "controlBehavior", 0)
        ));

        // Baseline degrade rules
        rules.put("degrade:gateway:capability:default", new RateLimitRule(
                "degrade", "gateway:capability:default",
                Map.of("grade", 0, "count", 500.0, "timeWindow", 10, "minRequestAmount", 5,
                        "statIntervalMs", 1000, "slowRatioThreshold", 0.5)
        ));

        log.info("Initialized {} baseline rate-limit rules", rules.size());
    }
}
