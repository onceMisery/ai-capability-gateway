package com.ai.gateway.domain.model;

import java.util.Locale;

/**
 * 运行面自然语言路由的曝光模式。
 *
 * <p>网关内置的 LLM 路由链路承担两项长期职责，二者都不退役：</p>
 * <ol>
 * <li><b>瘦客户端兼容面（TCS）</b>：服务没有 Agent 运行时的调用方（运维脚本、
 * IM Bot、低代码页面、回归 Demo）。</li>
 * <li><b>能力目录诊断面</b>：Manifest 中写给模型看的文本（displayName、purpose、
 * 字段描述、正负例、同义词）没有任何编译期判据，只有把
 * “授权过滤 → BM25 → 投影 → LLM 受限选择” 整条跑一遍才能验证其质量。
 * 该验证依赖网关内部中间态（授权后候选集、BM25 得分、alias 映射、
 * 可信字段剥离前后差异），按设计不出网关边界，因此无法外包给上层 Agent。</li>
 * </ol>
 *
 * <p>本枚举把“该模式允许做什么”内聚为自身行为，调用点只做询问而不做 switch
 * 判定；新增模式时只需在此处补齐语义，调用点无需修改（开闭原则）。</p>
 *
 * <p>模式语义矩阵：</p>
 * <table border="1">
 * <caption>模式与能力开关</caption>
 * <tr><th>模式</th><th>运行面 HTTP</th><th>多轮澄清</th><th>LLM 内核装配</th><th>管理面诊断</th></tr>
 * <tr><td>FULL</td><td>开</td><td>开</td><td>是</td><td>可用</td></tr>
 * <tr><td>COMPAT</td><td>开</td><td>关</td><td>是</td><td>可用</td></tr>
 * <tr><td>DIAGNOSTIC</td><td>关（501）</td><td>关</td><td>是</td><td>可用</td></tr>
 * <tr><td>DISABLED</td><td>关（501）</td><td>关</td><td>否</td><td>不可用</td></tr>
 * </table>
 *
 * @see ErrorCode#NL_ROUTER_DISABLED
 * @since 0.2.0
 */
public enum NlRouterMode {

    /** 全量：候选检索 + LLM 受限选择 + 多轮澄清会话。预发/测试环境默认。 */
    FULL(true, true, true),

    /** 单回合：候选检索 + LLM 受限选择，不建澄清会话。存量生产默认。 */
    COMPAT(true, false, true),

    /** 运行面关闭（501），仅保留管理面诊断能力。新建生产默认。 */
    DIAGNOSTIC(false, false, true),

    /** 完全关闭，LLM 端口不参与装配。仅用于不配置 LLM 凭据的最小部署。 */
    DISABLED(false, false, false);

    /** 默认模式：优先保证存量调用方不被打断。 */
    public static final NlRouterMode DEFAULT = COMPAT;

    private final boolean runtimeExposed;
    private final boolean clarificationEnabled;
    private final boolean llmKernelLoaded;

    NlRouterMode(boolean runtimeExposed, boolean clarificationEnabled, boolean llmKernelLoaded) {
        this.runtimeExposed = runtimeExposed;
        this.clarificationEnabled = clarificationEnabled;
        this.llmKernelLoaded = llmKernelLoaded;
    }

    /**
     * 运行面端点 {@code /api/v1/natural-language/**} 是否对外提供服务。
     *
     * @return 提供服务返回 {@code true}；否则调用方应收到 501 与稳定错误码
     */
    public boolean runtimeExposed() {
        return runtimeExposed;
    }

    /**
     * 是否允许创建多轮澄清会话。
     *
     * <p>关闭时仍可返回单次 {@code CLARIFICATION_REQUIRED}，但不落库交互记录，
     * 澄清对话状态不回到网关。</p>
     *
     * @return 允许创建澄清会话返回 {@code true}
     */
    public boolean clarificationEnabled() {
        return clarificationEnabled;
    }

    /**
     * LLM 选择内核（LlmRouterPort 及其下游）是否参与装配。
     *
     * <p>该值为 {@code false} 时不要求配置 LLM 凭据，同时 A2A 入站的
     * {@code GATEWAY_SELECTION} 兼容档不可用。</p>
     *
     * @return 需要装配 LLM 内核返回 {@code true}
     */
    public boolean llmKernelLoaded() {
        return llmKernelLoaded;
    }

    /**
     * 管理面能力目录诊断端点是否可用。
     *
     * <p>诊断依赖 LLM 内核完成“受限选择”一步，因此与 {@link #llmKernelLoaded()}
     * 同步：内核不装配则诊断不可用。</p>
     *
     * @return 诊断可用返回 {@code true}
     */
    public boolean diagnosticsCapable() {
        return llmKernelLoaded;
    }

    /**
     * 解析配置值，空值与空白值回落到 {@link #DEFAULT}。
     *
     * @param value 配置文本，允许 {@code null}、空白与任意大小写
     * @return 解析出的模式
     * @throws IllegalArgumentException 取值不在枚举范围内时抛出，避免拼写错误
     * 被静默降级为默认模式
     */
    public static NlRouterMode parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported NL router mode: " + value, e);
        }
    }
}
