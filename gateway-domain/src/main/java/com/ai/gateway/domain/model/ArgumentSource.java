package com.ai.gateway.domain.model;

/**
 * 协议参数绑定的受控来源。
 *
 * <p>网关强制区分"模型可见"与"模型不可见"参数。只有 {@link #MODEL} 来源的参数
 * 才出现在公开入参 Schema 中；其余所有来源均由网关以确定性方式注入，模型和用户
 * 均无法覆盖。</p>
 *
 * <table border="1">
 * <caption>参数来源与可见性</caption>
 * <tr><th>来源</th><th>含义</th><th>模型可见</th></tr>
 * <tr><td>{@link #MODEL}</td><td>来自 LLM 结构化输出的业务参数</td><td>是</td></tr>
 * <tr><td>{@link #PRINCIPAL}</td><td>来自已鉴权的 Principal（如 orgId）</td><td>否</td></tr>
 * <tr><td>{@link #CONSTANT}</td><td>来自清单中已确认的常量</td><td>否</td></tr>
 * <tr><td>{@link #SYSTEM}</td><td>平台上下文：traceId、deadline、idempotencyKey、locale</td><td>否</td></tr>
 * </table>
 *
 * <p>{@code SYSTEM} 只能读取平台内建的白名单路径，清单不得声明新的系统变量。</p>
 *
 * @see ArgumentBinding
 * @see FieldBinding
 * @see AttachmentBinding
 * @since 0.1.0
 */
public enum ArgumentSource {
    /**
     * 从模型的结构化输出中读取。只有 MODEL 来源的参数（或复合绑定中的 MODEL 叶子字段）
     * 可以出现在公开入参 Schema 中。
     */
    MODEL,

    /**
     * 从已鉴权的 Principal 中读取（如 orgId）。永远对模型不可见，且绝不允许来自
     * 请求体、查询参数或自定义请求头。
     */
    PRINCIPAL,

    /**
     * 从清单中声明的常量读取。对模型不可见。
     */
    CONSTANT,

    /**
     * 从执行上下文的平台白名单路径读取：
     * {@code /traceId}、{@code /deadlineEpochMs}、{@code /idempotencyKey}、
     * {@code /locale}。清单不得声明新的系统变量。
     */
    SYSTEM
}
