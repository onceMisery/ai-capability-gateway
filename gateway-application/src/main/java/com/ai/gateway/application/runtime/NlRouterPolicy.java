package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.NlRouterMode;

import java.util.Objects;

/**
 * 运行面自然语言路由的曝光策略。
 *
 * <p>把「模式语义」与「诊断面开关」聚合为一个不可变值对象，使运行面用例与管理面
 * 诊断用例共享同一份判定，避免两处各自 switch 模式枚举而出现语义漂移。</p>
 *
 * <p>与 {@link NlRouterMode} 的分工：模式枚举回答“该档位本身允许什么”，本策略在其上
 * 叠加部署侧开关（如运维临时关闭诊断端点），并对非法组合做归一化——
 * {@code DISABLED} 档下诊断恒不可用，即使配置显式打开。</p>
 *
 * @param mode 运行面模式
 * @param diagnosticsEnabled 部署侧诊断开关；仅在模式本身支持诊断时生效
 * @param diagnosticsMaxCandidates 单次诊断输出的候选上限，防止一次调用倾泻整个目录
 * @since 0.2.0
 */
public record NlRouterPolicy(NlRouterMode mode,
                             boolean diagnosticsEnabled,
                             int diagnosticsMaxCandidates) {

    /** 诊断候选上限的兜底默认值。 */
    public static final int DEFAULT_DIAGNOSTICS_MAX_CANDIDATES = 10;

    public NlRouterPolicy {
        Objects.requireNonNull(mode, "mode must not be null");
        if (diagnosticsMaxCandidates <= 0) {
            throw new IllegalArgumentException("diagnosticsMaxCandidates must be positive");
        }
    }

    /**
     * 构造全量模式策略，供既有调用方与单元测试保持原有行为。
     *
     * @return {@code FULL} 档且诊断开启的策略
     */
    public static NlRouterPolicy full() {
        return new NlRouterPolicy(NlRouterMode.FULL, true, DEFAULT_DIAGNOSTICS_MAX_CANDIDATES);
    }

    /**
     * 按模式构造策略，诊断开关取模式自身的默认能力。
     *
     * @param mode 运行面模式
     * @return 对应策略
     */
    public static NlRouterPolicy of(NlRouterMode mode) {
        return new NlRouterPolicy(mode, true, DEFAULT_DIAGNOSTICS_MAX_CANDIDATES);
    }

    /**
     * 运行面 {@code /api/v1/natural-language/**} 是否受理请求。
     *
     * @return 受理返回 {@code true}；否则调用方应收到
     * {@link com.ai.gateway.domain.model.ErrorCode#NL_ROUTER_DISABLED}
     */
    public boolean runtimeQueryAllowed() {
        return mode.runtimeExposed();
    }

    /**
     * 是否允许创建多轮澄清会话（落库 {@code NlInteraction}）。
     *
     * @return 允许返回 {@code true}
     */
    public boolean clarificationSessionAllowed() {
        return mode.clarificationEnabled();
    }

    /**
     * 管理面能力目录诊断端点是否可用。
     *
     * @return 模式支持诊断且部署侧未关闭时返回 {@code true}
     */
    public boolean diagnosticsAllowed() {
        return mode.diagnosticsCapable() && diagnosticsEnabled;
    }
}
