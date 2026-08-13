package com.ai.gateway.domain.model;

/**
 * Identifies the wire protocol used to invoke a capability.
 *
 * <p>The initial production release supports {@link #DUBBO} only.
 * {@link #REST} and {@link #GRPC} are evolution protocols (Section 14).</p>
 *
 * <p>All protocols share the same lifecycle, confirmation, natural-language
 * semantics, input/output JSON Schema, Principal injection, authorization,
 * risk, audit, and write-operation state machine. Protocol
 * differences exist only within {@code spec.invocation} and the adapter
 * internals.</p>
 *
 * @see ProtocolBinding
 * @since 0.1.0
 */
public enum Protocol {
    /**
     * Apache Dubbo generic invocation (Section 12). The initial release
     * protocol.
     */
    DUBBO,

    /**
     * REST over HTTP. OpenAPI 3.1 is the primary import source
     *. Evolution protocol.
     */
    REST,

    /**
     * gRPC unary RPC using confirmed FileDescriptorSet.
     * Evolution protocol.
     */
    GRPC
}
