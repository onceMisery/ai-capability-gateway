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
 * 在运行时管理 Sentinel 限流规则的服务。
 *
 * <p>提供 Sentinel 流控与降级规则的增删改查操作。规则保存在内存中，
 * 重启时重置为硬编码基线。管理控制台通过该服务查看与调整限流阈值。</p>
 *
 * <p>当 Sentinel 不在类路径上时，该服务仍会在内存中维护规则列表，
 * 以供管理控制台展示。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Service
public class SentinelRuleAdminService implements RateLimitAdminPort {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleAdminService.class);

    private final Map<String, RateLimitRule> rules = new ConcurrentHashMap<>();

    /**
     * 构造一个带基线规则的 SentinelRuleAdminService。
     */
    public SentinelRuleAdminService() {
        initBaselineRules();
    }

    /**
     * 返回当前所有限流规则。
     *
     * @return 不可修改的规则列表；不会为 {@code null}
     */
    public List<RateLimitRule> listRules() {
        return Collections.unmodifiableList(new ArrayList<>(rules.values()));
    }

    /**
     * 新增或更新一条限流规则。
     *
     * @param rule 待新增或更新的规则
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

        // 基线降级规则
        rules.put("degrade:gateway:capability:default", new RateLimitRule(
                "degrade", "gateway:capability:default",
                Map.of("grade", 0, "count", 500.0, "timeWindow", 10, "minRequestAmount", 5,
                        "statIntervalMs", 1000, "slowRatioThreshold", 0.5)
        ));

        log.info("Initialized {} baseline rate-limit rules", rules.size());
    }
}
