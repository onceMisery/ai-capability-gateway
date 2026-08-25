package com.ai.gateway.domain.model;

/**
 * 网关执行流水线的稳定错误码。
 *
 * <p>定义完整的错误分类法。对外错误响应绝不能包含堆栈、内部地址、接口类名或敏感参数。
 * 审计日志将稳定错误码与受控诊断摘要一并记录。</p>
 *
 * <p>{@code retryable} 标志给出默认重试策略：</p>
 * <ul>
 * <li>{@code false} - 错误为终态，重试无济于事。</li>
 * <li>{@code true} - 重试可能成功，但受风险等级、幂等策略及下文具体规则约束。</li>
 * </ul>
 *
 * <p>注意：部分错误码的重试语义较为微妙，取决于调用方类型（用户 vs 网关）与操作的
 * 风险等级。详见各常量的文档说明。</p>
 *
 * @see InvocationResult
 * @see AuditEvent
 * @since 0.1.0
 */
public enum ErrorCode {

    /**
     * 调用方身份无效或无法校验。不可重试。
     */
    AUTHENTICATION_FAILED(false),

    /**
     * 已鉴权的主体无权查看或执行所请求的能力。不可重试。
     */
    PERMISSION_DENIED(false),

    /** 写能力必须使用 Prepare/Confirm 协议。 */
    CONFIRMATION_REQUIRED(false),

    /**
     * 检索与阈值过滤后没有任何能力匹配该自然语言请求。不可重试。
     */
    NO_CAPABILITY_MATCH(false),

    /**
     * 模型或网关要求用户提供补充信息或消歧请求。用户补充后可重试。
     */
    CLARIFICATION_REQUIRED(true),

    /**
     * 模型的结构化输出未通过 Schema 或业务校验。网关可尝试一次自动修复，
     * 之后对该请求即为终态错误。
     */
    INVALID_MODEL_OUTPUT(true),

    /**
     * 绑定参数不满足能力的入参契约。用户修正参数后可重试。
     */
    ARGUMENT_VALIDATION_FAILED(true),

    /**
     * 所选能力已下线、退役，或其版本不再可用。不可重试。
     */
    CAPABILITY_UNAVAILABLE(false),

    /** 配置的语言模型 Provider 不可达或不健康。 */
    LLM_UNAVAILABLE(true),

    /** 网关的有界资源在未排队的情况下拒绝了请求。 */
    RATE_LIMITED(true),

    /**
     * Provider 超时。是否可重试取决于风险等级与幂等策略：只读操作可按策略重试；
     * 写操作必须遵循两阶段恢复协议。
     */
    PROVIDER_TIMEOUT(true),

    /**
     * Provider 返回业务级失败（如非成功的信封码）。通常不可重试，
     * 因为业务状态已改变或该状况持续存在。
     */
    PROVIDER_REJECTED(false),

    /**
     * 发生协议级或响应契约错误（如响应结构意外、缺失信封路径）。只读操作
     * 可按韧性策略重试。
     */
    PROTOCOL_ERROR(true),

    /**
     * 响应超过配置的最大字节限制。不可重试。
     */
    RESULT_TOO_LARGE(false),

    /**
     * 写操作结果不确定：请求可能已到达 Provider，但网关未收到确定性响应。
     * 只能通过状态查询或对账解决。
     */
    EXECUTION_UNKNOWN(false),

    /**
     * 运行面自然语言路由未对外曝光（{@link NlRouterMode#runtimeExposed()} 为
     * {@code false}）。不可重试：调用方应改用结构化工具调用或 MCP 入口。
     *
     * <p>这是一项曝光策略结果，不代表能力缺失——LLM 路由内核在
     * {@code DIAGNOSTIC} 档下仍然保留，仅通过管理面诊断端点暴露。</p>
     */
    NL_ROUTER_DISABLED(false);

    private final boolean retryable;

    ErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    /**
     * 返回该错误默认是否可重试。
     *
     * @return 若受风险与幂等策略约束下重试可能成功则为 {@code true}；
     * 若为终态错误则为 {@code false}
     */
    public boolean isRetryable() {
        return retryable;
    }
}
