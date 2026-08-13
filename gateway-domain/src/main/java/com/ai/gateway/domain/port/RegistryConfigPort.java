package com.ai.gateway.domain.port;

/**
 * Port for resolving registry configuration for protocol bindings.
 *
 * <p>(Protocol Binding) specifies that {@code registryRef}
 * references an operationally pre-configured registry. Manifests must not
 * carry usernames, passwords, or arbitrary registry addresses. The
 * registry is resolved at runtime from platform-side configuration.</p>
 *
 * <p>(Keys and Network): database, registry, model, and
 * Provider credentials are provided by a Secret Manager or Workload
 * Identity. Configuration files and Manifests must never contain secrets.
 * The resolved {@code username} references a secret key rather than a
 * plaintext credential.</p>
 *
 * <p>Adapters implementing this port resolve registry references from
 * platform-side configuration (e.g., application configuration or a
 * service discovery system). The port is a pure abstraction with no
 * framework dependencies.</p>
 *
 * @since 0.1.0
 */
public interface RegistryConfigPort {

    /**
     * Resolves the registry configuration for the given registry reference.
     *
     * <p>: the {@code registryRef} declared in a Manifest's
     * {@code spec.invocation} is resolved to its actual protocol, address,
     * port, and credential reference at runtime. The Manifest itself never
     * carries credentials or arbitrary addresses.</p>
     *
     * @param registryRef the registry reference declared in the Manifest
     * @return the resolved registry configuration; never {@code null}
     * @throws IllegalArgumentException if the registry reference is unknown
     */
    RegistryConfig resolve(String registryRef);

    /**
     * The resolved registry configuration.
     *
     * <p> the {@code username} references a
     * secret key to be resolved by the {@link SecretManager}, not a
     * plaintext credential. The {@code address} and {@code port} are
     * operationally pre-configured and must not be self-declared in
     * Manifests.</p>
     *
     * @param protocol the registry protocol (e.g., "nacos", "zookeeper")
     * @param address the registry network address
     * @param port the registry port
     * @param username the secret key reference for the registry credential
     */
    record RegistryConfig(String protocol, String address, int port, String username) {
    }
}
