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
 * {@link SentinelRateLimiterAdapter} 的单元测试。
 *
 * <p>验证编程式 Sentinel 集成：无规则时可获取资源；配置零阈值流控规则时
 * 获取被阻断（快速失败）。</p>
 *
 * @author cmiracle@163.com
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
