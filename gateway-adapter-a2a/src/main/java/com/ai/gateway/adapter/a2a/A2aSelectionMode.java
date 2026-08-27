package com.ai.gateway.adapter.a2a;

import java.util.List;
import java.util.Locale;

/**
 * A2A 入站请求的能力选择模式。
 *
 * <p>A2A 的 Task 天然是自然语言的，而本网关的既有约束是「模型不是信任边界」。这三种模式
 * 表达的是<b>由谁做能力选择</b>，而不是「要不要用 LLM」：</p>
 * <ul>
 * <li>{@link #DELEGATED_SELECTION}——默认。网关只做检索与授权，把候选集回给对端由对端自己的
 * 模型选择，网关侧<b>零 LLM 调用</b>。这是最省成本也最可审计的形态：选择责任落在发起方，
 * 网关只对「候选集是否都已授权」「参数是否合法」负责。</li>
 * <li>{@link #GATEWAY_SELECTION}——兼容形态。复用网关内的 NL 路由链（{@code LlmRouterPort}
 * 与选择决策处理器），一跳完成。它要求 NL 路由处于启用状态，因此本模式与 NL 路由的开关存在
 * 硬耦合，必须由生产配置校验器显式检查。</li>
 * <li>{@link #STRUCTURED_ONLY}——最严形态。只接受结构化的首跳请求，自由文本直接拒绝。
 * 适用于对端已经知道要用哪个域、不需要任何语义匹配的机器对机器集成。</li>
 * </ul>
 *
 * <p>三种模式共享同一条执行路径：无论选择由谁做出，最终都要经过确定性的重新授权与参数校验。
 * 模式只影响「选择在哪一跳发生」，不影响任何安全判定。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public enum A2aSelectionMode {

    /** 委派选择：网关返回候选集，对端模型选择，网关不调用 LLM。 */
    DELEGATED_SELECTION(false, true, true),

    /** 网关选择：复用网关内 NL 路由链，一跳完成，要求 NL 路由启用。 */
    GATEWAY_SELECTION(true, true, false),

    /** 仅结构化：只接受结构化首跳请求，自由文本一律拒绝。 */
    STRUCTURED_ONLY(false, false, true);

    /** 入站声明的入参媒体类型：结构化优先，便于对端直接发 DataPart。 */
    private static final List<String> STRUCTURED_INPUT_MODES = List.of("application/json");

    /** 同时接受自由文本与结构化输入时声明的入参媒体类型。 */
    private static final List<String> TEXT_AND_STRUCTURED_INPUT_MODES =
            List.of("text/plain", "application/json");

    /** 出参媒体类型固定：结果始终以结构化数据为主、文本摘要为辅。 */
    private static final List<String> OUTPUT_MODES = List.of("application/json", "text/plain");

    private final boolean requiresNlRouter;
    private final boolean acceptsFreeText;
    private final boolean acceptsStructuredSelection;

    A2aSelectionMode(boolean requiresNlRouter, boolean acceptsFreeText,
                     boolean acceptsStructuredSelection) {
        this.requiresNlRouter = requiresNlRouter;
        this.acceptsFreeText = acceptsFreeText;
        this.acceptsStructuredSelection = acceptsStructuredSelection;
    }

    /**
     * @return 本模式是否要求网关内的 NL 路由处于启用状态
     */
    public boolean requiresNlRouter() {
        return requiresNlRouter;
    }

    /**
     * @return 本模式是否接受自由文本形态的首跳请求
     */
    public boolean acceptsFreeText() {
        return acceptsFreeText;
    }

    /**
     * @return 本模式是否接受结构化形态（{@code DataPart}）的选择结果
     */
    public boolean acceptsStructuredSelection() {
        return acceptsStructuredSelection;
    }

    /**
     * 返回应在 AgentCard 的 skill 上声明的入参媒体类型。
     *
     * <p>声明必须与实际受理的形态一致：若模式已经拒绝自由文本，却仍在卡片上声明
     * {@code text/plain}，对端就会按声明发出必然被拒的请求——这既浪费一跳，
     * 也让对端无法从卡片上判断正确的调用方式。</p>
     *
     * @return 不可变的媒体类型列表
     */
    public List<String> inputModes() {
        return acceptsFreeText ? TEXT_AND_STRUCTURED_INPUT_MODES : STRUCTURED_INPUT_MODES;
    }

    /**
     * @return 应在 AgentCard 的 skill 上声明的出参媒体类型
     */
    public List<String> outputModes() {
        return OUTPUT_MODES;
    }

    /**
     * 宽松解析配置值。
     *
     * <p>无法识别的值归为 {@link #DELEGATED_SELECTION}：它是三者中唯一不依赖 LLM、
     * 也不放宽任何校验的模式，作为配置错误时的落点最安全。</p>
     *
     * @param value 配置值，允许为 {@code null}
     * @return 解析结果；无法识别时返回 {@link #DELEGATED_SELECTION}
     */
    public static A2aSelectionMode from(String value) {
        if (value == null || value.isBlank()) {
            return DELEGATED_SELECTION;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (A2aSelectionMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return DELEGATED_SELECTION;
    }
}
