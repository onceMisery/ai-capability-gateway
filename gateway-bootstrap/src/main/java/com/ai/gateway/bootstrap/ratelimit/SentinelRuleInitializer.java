package com.ai.gateway.bootstrap.ratelimit;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在启动时加载初始的硬编码 Sentinel 流控与降级规则。
 *
 * <p>依据技术选型文档 §5.4–5.5 及已确认决策（先硬编码规则，后续接入 Nacos
 * DataSource），注册以下规则：</p>
 * <ul>
 * <li>{@code gateway:global} — 全局 QPS 2000，快速失败。</li>
 * <li>{@code gateway:llm:routing} — LLM 路由 QPS 20，最多排队 2 秒。</li>
 * <li>{@code gateway:capability:{id}} — 按能力维度降级规则
 * （异常比例 &gt; 50% 且慢调用比例 &gt; 80%、RT &gt; 3 秒）。</li>
 * </ul>
 *
 * <p>按能力的流控规则以能力 id 为键；初始集合为空，随规则新增而增长
 * （生产部署会从 Nacos DataSource 加载）。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
public class SentinelRuleInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleInitializer.class);

    private final double globalQps;
    private final double llmQps;
    private final int llmMaxQueueingMs;
    private final List<String> capabilityIds;
    private final Map<String, Double> dimensionQps;

    /**
     * 构造一个新的初始化器。
     *
     * @param globalQps 全局 QPS 阈值
     * @param llmQps LLM 路由 QPS 阈值
     * @param llmMaxQueueingMs LLM 路由的最大排队时间
     * @param capabilityIds 用于初始化降级规则的能力 id 列表
     */
    public SentinelRuleInitializer(double globalQps, double llmQps, int llmMaxQueueingMs,
                                   List<String> capabilityIds) {
        this(globalQps, llmQps, llmMaxQueueingMs, capabilityIds, Map.of());
    }

    /**
     * 构造一个新的初始化器。
     *
     * <p>暴露面维度以「资源键 → QPS」的映射传入，而不是逐个维度的形参：新增一个入口
     * （MCP 的工具清单变更通知、A2A 的入站 Task 等）时只需在调用方多放一个键，
     * 本类无需再开一个构造重载，也不会出现「配置里加了阈值但忘了注册规则」的静默漂移。
     * 键必须与取用限流的一侧共用同一个常量（{@code McpRateLimiter}、
     * {@code A2aRateLimiter} 的资源键常量），否则规则会挂在一个没人访问的资源上——
     * 这种失效形态不会报错，只会表现为「配了阈值却从不生效」。</p>
     *
     * @param globalQps 全局 QPS 阈值
     * @param llmQps LLM 路由 QPS 阈值
     * @param llmMaxQueueingMs LLM 路由的最大排队时间
     * @param capabilityIds 用于初始化降级规则的能力 id 列表
     * @param dimensionQps 各暴露面维度的 QPS 阈值，键为限流资源键
     */
    public SentinelRuleInitializer(double globalQps, double llmQps, int llmMaxQueueingMs,
                                   List<String> capabilityIds,
                                   Map<String, Double> dimensionQps) {
        this.globalQps = globalQps;
        this.llmQps = llmQps;
        this.llmMaxQueueingMs = llmMaxQueueingMs;
        this.capabilityIds = capabilityIds == null ? List.of() : List.copyOf(capabilityIds);
        this.dimensionQps = dimensionQps == null
                ? Map.of() : new LinkedHashMap<>(dimensionQps);
    }

    /**
     * 将流控与降级规则加载进 Sentinel。
     */
    @Override
    public void afterPropertiesSet() {
        List<FlowRule> flowRules = getFlowRules();
        for (Map.Entry<String, Double> entry : dimensionQps.entrySet()) {
            addDimensionRule(flowRules, entry.getKey(), entry.getValue());
        }

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

    private @NonNull List<FlowRule> getFlowRules() {
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
        return flowRules;
    }

    /**
     * 为一个暴露面维度注册全局流控规则。
     *
     * <p>非正阈值直接抛出而不是跳过：一条被跳过的规则表现为「这个入口不限流」，
     * 而配置里明明写着一个数字——把它当成配置错误在启动期拦下，比在生产被刷穿时再发现要好。</p>
     */
    private static void addDimensionRule(List<FlowRule> flowRules, String dimension,
                                         Double qps) {
        if (qps == null || qps <= 0) {
            throw new IllegalArgumentException("Rate-limit QPS must be positive: " + dimension);
        }
        FlowRule rule = new FlowRule("gateway:" + dimension + ":global");
        rule.setCount(qps);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setStrategy(RuleConstant.STRATEGY_DIRECT);
        rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        flowRules.add(rule);
    }
}
