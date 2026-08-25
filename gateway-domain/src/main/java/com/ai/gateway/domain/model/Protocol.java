package com.ai.gateway.domain.model;

/**
 * 标识调用能力所使用的线缆协议。
 *
 * <p>初始生产版本仅支持 {@link #DUBBO}。{@link #REST} 与 {@link #GRPC} 为演进协议
 *（第 14 节）。</p>
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
    GRPC
}
