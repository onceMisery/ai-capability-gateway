package com.ai.gateway.domain.model;

/**
 * 标识调用能力所使用的线缆协议。
 *
 * <p>初始生产版本仅支持 {@link #DUBBO}。{@link #REST} 与 {@link #GRPC} 为演进协议
 *（第 14 节），{@link #A2A} 为出站委托协议（设计 §3.7）。</p>
 *
 * <p>本枚举<b>不被任何 {@code switch} 覆盖、也不被 {@code values()} 全量枚举</b>：协议分发
 * 由 {@code ProtocolRoutingInvocationAdapter} 按各适配器自报的 {@code protocol()} 建表完成。
 * 因此新增一个取值只需新增一个适配器实现，既有代码无需改动（开闭原则）。</p>
 *
 * <p>所有协议共享相同的生命周期、确认流程、自然语言语义、入参/出参 JSON Schema、
 * Principal 注入、鉴权、风险、审计与写操作状态机。协议差异仅存在于 {@code spec.invocation}
 * 与适配器内部。</p>
 *
 * @see ProtocolBinding
 * @since 0.1.0
 */
public enum Protocol {
    /**
     * Apache Dubbo 泛化调用（第 12 节）。初始版本的协议。
     */
    DUBBO,

    /**
     * 基于 HTTP 的 REST。OpenAPI 3.1 是主要导入来源。演进协议。
     */
    REST,

    /**
     * 使用已确认 FileDescriptorSet 的 gRPC 一元 RPC。演进协议。
     */
    GRPC,

    /**
     * A2A 出站委托：能力的提供方是一个远端 Agent（设计 §3.7）。
     *
     * <p>把远端 Agent 收成一种协议绑定，而不是让上层 Agent 平台直连，是为了让
     * 「Domain Agent 也只是一种被治理的能力提供者」这句话在代码里成立：Manifest 仍是事实源，
     * 入参校验、Principal 注入、脱敏、审计、韧性策略都不因对端是 Agent 而被跳过。</p>
     *
     * <p><b>复用既有绑定字段，不新增记录组件</b>（与 {@link #REST}、{@link #GRPC} 一致）：</p>
     * <ul>
     * <li>{@code registryRef} —— 运维预配置的远端 Agent 引用键。清单<b>不得</b>携带内联
     * AgentCard 地址：一旦允许，导入一份清单就等于给网关新增一个出站目标，
     * 而出站目标是运维边界而非能力作者的自由度。</li>
     * <li>{@code interfaceName} —— 远端 Agent 名称，仅用于审计标签与人工核对。</li>
     * <li>{@code method} —— 远端技能标识（{@code skillId}）。</li>
     * <li>{@code arguments} —— 与 {@code parameterTypes} 位置一一对应的具名参数；
     * 出站消息只携带这些结构化参数，不携带自由文本。</li>
     * </ul>
     */
    A2A
}
