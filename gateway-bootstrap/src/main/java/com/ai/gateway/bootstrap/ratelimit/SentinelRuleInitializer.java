package com.ai.gateway.bootstrap.ratelimit;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads the initial hardcoded Sentinel flow and degrade rules at startup.
 *
 * <p>Per the tech-selection doc §5.4–5.5 and the confirmed decision
 * (hardcoded rules first, Nacos DataSource later), the following rules are
 * registered:</p>
 * <ul>
 * <li>{@code gateway:global} — global QPS 2000, fast fail.</li>
 * <li>{@code gateway:llm:routing} — LLM routing QPS 20, queue up to 2s.</li>
 * <li>{@code gateway:capability:{id}} — per-capability degrade rules
 * (exception ratio &gt; 50% and slow-call ratio &gt; 80% with RT &gt; 3s).</li>
 * </ul>
 *
 * <p>Per-capability flow rules are keyed by capability id; the initial set
 * is empty and grows as rules are added (a production deployment would load
 * them from a Nacos DataSource).</p>
 *
 * @since 0.1.0
 */
public class SentinelRuleInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleInitializer.class);

    private final double globalQps;
    private final double llmQps;
    private final int llmMaxQueueingMs;
    private final List<String> capabilityIds;

    /**
     * Constructs a new initializer.
     *
     * @param globalQps the global QPS threshold
     * @param llmQps the LLM routing QPS threshold
     * @param llmMaxQueueingMs the maximum queueing time for LLM routing
     * @param capabilityIds the capability ids to seed degrade rules for
     */
    public SentinelRuleInitializer(double globalQps, double llmQps, int llmMaxQueueingMs,
                                   List<String> capabilityIds) {
        this.globalQps = globalQps;
        this.llmQps = llmQps;
        this.llmMaxQueueingMs = llmMaxQueueingMs;
        this.capabilityIds = capabilityIds == null ? List.of() : List.copyOf(capabilityIds);
    }

    /**
     * Loads the flow and degrade rules into Sentinel.
     */
    @Override
    public void afterPropertiesSet() {
        List<FlowRule> flowRules = new ArrayList<>();

        FlowRule global = new FlowRule("gateway:global");
        global.setCount(globalQps);
        global.setGrade(RuleConstant.FLOW_GRADE_QPS);
        global.setStrategy(RuleConstant.STRATEGY_DIRECT);
        global.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        flowRules.add(global);

        FlowRule llm = new FlowRule("gateway:llm:routing");
        llm.setCount(llmQps);
        llm.setGrade(RuleConstant.FLOW_GRADE_QPS);
        llm.setStrategy(RuleConstant.STRATEGY_DIRECT);
        llm.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER);
        llm.setMaxQueueingTimeMs(llmMaxQueueingMs);
        flowRules.add(llm);

        FlowRuleManager.loadRules(flowRules);

        List<DegradeRule> degradeRules = new ArrayList<>();
        for (String capabilityId : capabilityIds) {
            String resource = "gateway:capability:" + capabilityId;

            DegradeRule exceptionRatio = new DegradeRule(resource);
            exceptionRatio.setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType());
            exceptionRatio.setCount(0.5);
            exceptionRatio.setTimeWindow(30);
            exceptionRatio.setStatIntervalMs(10000);
            exceptionRatio.setMinRequestAmount(5);
            degradeRules.add(exceptionRatio);

            DegradeRule slowCall = new DegradeRule(resource);
            slowCall.setGrade(CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType());
            slowCall.setCount(0.8);
            slowCall.setSlowRatioThreshold(3000);
            slowCall.setTimeWindow(60);
            slowCall.setStatIntervalMs(10000);
            slowCall.setMinRequestAmount(5);
            degradeRules.add(slowCall);
        }
        DegradeRuleManager.loadRules(degradeRules);

        log.info("Sentinel rules initialized: flowRules={}, degradeRules={}",
                flowRules.size(), degradeRules.size());
    }
}
