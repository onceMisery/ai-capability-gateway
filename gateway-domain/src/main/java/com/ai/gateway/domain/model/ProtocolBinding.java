package com.ai.gateway.domain.model;

import java.util.List;
import java.util.Map;

/**
 * The deterministic protocol invocation binding for a capability.
 *
 * <p>Defines the protocol binding as the execution configuration
 * that does <strong>not</strong> enter the model context. It contains all
 * information needed to perform a generic invocation without loading any
 * business API JAR at runtime.</p>
 *
 * <p>For Dubbo (Section 12), the binding includes:</p>
 * <ul>
 * <li>{@code registryRef} - references an operationally pre-configured
 * registry; Manifests must not carry usernames, passwords, or
 * arbitrary registry addresses.</li>
 * <li>{@code interfaceName}, {@code group}, {@code version}, {@code method} -
 * the Dubbo service coordinates.</li>
 * <li>{@code parameterTypes} - must correspond one-to-one with
 * {@code arguments} positions and {@code protocolType} values.</li>
 * <li>{@code serialization} - must belong to the platform serialization
 * whitelist and be validated during compatibility
 * testing.</li>
 * <li>{@code arguments} - the ordered argument bindings.</li>
 * <li>{@code attachments} - the whitelisted attachment bindings
 *.</li>
 * </ul>
 *
 * <p>The gateway does not load the {@code interfaceName} or
 * {@code parameterTypes} classes at compile or runtime; they exist only as
 * type-name strings for generic invocation.</p>
 *
 * @param protocol the wire protocol
 * @param registryRef the operationally pre-configured registry reference
 * @param interfaceName the fully-qualified service interface name
 * @param group the service group
 * @param version the service version
 * @param method the method name to invoke
 * @param parameterTypes the list of fully-qualified parameter type names
 * @param serialization the serialization method (must be in the platform whitelist)
 * @param arguments the ordered argument bindings
 * @param attachments the attachment bindings keyed by whitelisted attachment name
 * @since 0.1.0
 */
public record ProtocolBinding(
        Protocol protocol,
        String registryRef,
        String interfaceName,
        String group,
        String version,
        String method,
        List<String> parameterTypes,
        String serialization,
        List<ArgumentBinding> arguments,
        Map<String, AttachmentBinding> attachments
) {

    /**
     * Compact constructor performing defensive copying.
     *
     * @param protocol the wire protocol
     * @param registryRef the registry reference
     * @param interfaceName the service interface name
     * @param group the service group
     * @param version the service version
     * @param method the method name
     * @param parameterTypes the parameter type names
     * @param serialization the serialization method
     * @param arguments the argument bindings
     * @param attachments the attachment bindings
     */
    public ProtocolBinding {
        java.util.Objects.requireNonNull(protocol, "protocol must not be null");
        java.util.Objects.requireNonNull(interfaceName, "interfaceName must not be null");
        java.util.Objects.requireNonNull(method, "method must not be null");
        java.util.Objects.requireNonNull(parameterTypes, "parameterTypes must not be null");
        java.util.Objects.requireNonNull(arguments, "arguments must not be null");
        parameterTypes = List.copyOf(parameterTypes);
        arguments = List.copyOf(arguments);
        if (attachments != null) {
            attachments = Map.copyOf(attachments);
        }
    }
}
