package com.ai.gateway.domain.port;

/**
 * Port for secret management.
 *
 * <p>(Keys and Network) specifies that credentials for
 * databases, registries, models, and Providers are provided by a Secret
 * Manager or Workload Identity. Configuration files and Manifests must
 * never contain secrets.</p>
 *
 * <p>Production uses outbound network whitelisting. The management plane
 * and invocation plane use separate entry points and network policies.
 * Providers preferably use mTLS; certificate rotation must not depend on
 * rebuilding images.</p>
 *
 * <p>Adapters implementing this port integrate with the platform's secret
 * management system (e.g., HashiCorp Vault, Kubernetes Secrets, or cloud
 * KMS). The port is a pure abstraction with no framework dependencies.</p>
 *
 * @since 0.1.0
 */
public interface SecretManager {

    /**
     * Retrieves the secret value associated with the given key.
     *
     * <p>: secrets are resolved at runtime from the Secret
     * Manager or Workload Identity, never from configuration files or
     * Manifests. The key typically references a pre-configured secret
     * path or alias.</p>
     *
     * @param key the secret key or path
     * @return the secret value; never {@code null}
     * @throws RuntimeException if the secret cannot be resolved
     */
    String getSecret(String key);
}
