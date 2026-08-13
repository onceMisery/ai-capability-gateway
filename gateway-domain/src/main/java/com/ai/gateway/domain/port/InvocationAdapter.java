package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ValidationReport;

/**
 * Unified protocol invocation adapter port.
 *
 * <p>Specifies that all protocols implement the same
 * application-layer port. The adapter must not perform natural-language
 * routing, user authorization, or capability state changes.</p>
 *
 * <p>The initial production release supports {@link Protocol#DUBBO Dubbo}
 * only. {@link Protocol#REST REST} and {@link Protocol#GRPC
 * gRPC} are evolution protocols (Section 14). All protocols share the same
 * lifecycle, confirmation, natural-language semantics, input/output JSON
 * Schema, Principal injection, authorization, risk, audit, and
 * write-operation state machine. Protocol differences exist
 * only within {@code spec.invocation} and the adapter internals.</p>
 *
 * <p>The neutral request ({@link InvocationRequest}) contains the
 * capability identity, deadline budget, idempotency key, trace context,
 * and the fully-bound, positionally-ordered protocol arguments. The
 * adapter must not perform NL routing, user authorization, or capability
 * state changes.</p>
 *
 * <p>The neutral result ({@link InvocationResult}) contains only
 * JSON-compatible data, a protocol status, a stable error code, an error
 * message, and call metadata. It does not contain raw protocol objects,
 * stack traces, internal addresses, interface class names, or sensitive
 * parameters.</p>
 *
 * <p>Adapters implementing this port handle the specific protocol's
 * generic invocation. The port is a pure abstraction with no framework
 * dependencies.</p>
 *
 * @see Protocol
 * @see ProtocolBinding
 * @see ValidationReport
 * @see InvocationRequest
 * @see InvocationResult
 * @since 0.1.0
 */
public interface InvocationAdapter {

    /**
     * Returns the wire protocol this adapter handles.
     *
     * <p>: each adapter implementation serves exactly one
     * protocol. The gateway selects the appropriate adapter based on the
     * {@link ProtocolBinding#protocol()} declared in the capability
     * manifest.</p>
     *
     * @return the wire protocol; never {@code null}
     */
    Protocol protocol();

    /**
     * Validates the protocol binding for structural, semantic, and
     * security compliance.
     *
     * <p>: the adapter validates the binding configuration
     * before it is used for invocation. This is part of the 10-step
     * validation pipeline.</p>
     *
     * <p>For Dubbo (Section 12), validation includes verifying that
     * {@code parameterTypes} correspond one-to-one with argument positions,
     * the {@code serialization} belongs to the platform whitelist
     * and {@code registryRef} references a pre-configured
     * registry.</p>
     *
     * @param binding the protocol binding to validate
     * @return the validation report; valid only if {@code errors} is empty
     */
    ValidationReport validate(ProtocolBinding binding);

    /**
     * Invokes the target capability using the fully-bound request.
     *
     * <p>: the neutral request contains the capability
     * identity, deadline budget, idempotency key, trace context, and
     * the ordered, fully-bound protocol arguments. The adapter converts
     * the protocol result to a JSON-compatible tree.</p>
     *
     * <p>Defines the result processing order after the
     * adapter returns: the adapter converts the protocol result to JSON,
     * then the gateway checks response size/depth/collection length,
     * applies envelope rules, projection whitelist, field redactions, and
     * validates the public output Schema.</p>
     *
     * @param request the protocol-neutral invocation request
     * @return the protocol-neutral invocation result; never {@code null}
     */
    InvocationResult invoke(InvocationRequest request);
}
