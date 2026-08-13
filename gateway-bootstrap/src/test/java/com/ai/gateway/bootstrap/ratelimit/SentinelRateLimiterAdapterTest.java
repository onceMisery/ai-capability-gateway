package com.ai.gateway.bootstrap.ratelimit;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SentinelRateLimiterAdapter}.
 *
 * <p>Verifies the programmatic Sentinel integration: with no rule a resource
 * is acquired; with a zero-threshold flow rule the acquisition is blocked
 * (Fail Fast).</p>
 */
class SentinelRateLimiterAdapterTest {

    private final SentinelRateLimiterAdapter adapter = new SentinelRateLimiterAdapter();

    @AfterEach
    void tearDown() {
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    @DisplayName("acquire succeeds when no rule is configured")
    void acquireSucceedsWithoutRule() {
        assertThat(adapter.tryAcquire("user", "u-1", 1)).isTrue();
    }

    @Test
    @DisplayName("zero-threshold flow rule blocks acquisition")
    void zeroThresholdRuleBlocks() {
        FlowRule rule = new FlowRule("gateway:capability:order.detail.query");
        rule.setCount(0);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        FlowRuleManager.loadRules(List.of(rule));

        assertThat(adapter.tryAcquire("capability", "order.detail.query", 1)).isFalse();
    }

    @Test
    @DisplayName("high-threshold rule allows acquisition")
    void highThresholdRuleAllows() {
        FlowRule rule = new FlowRule("gateway:global");
        rule.setCount(10000);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        FlowRuleManager.loadRules(List.of(rule));

        assertThat(adapter.tryAcquire("global", "all", 1)).isTrue();
    }
}
