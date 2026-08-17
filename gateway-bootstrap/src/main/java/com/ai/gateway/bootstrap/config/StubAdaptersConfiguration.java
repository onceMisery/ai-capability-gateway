package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.CompatibilityTestPort;
import com.ai.gateway.domain.port.EncryptionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Inline stub adapters for ports that have no dedicated adapter
 * implementation yet.
 *
 * <p>These stubs follow the spec's initial-release degradation rules and are
 * <b>development only</b>:</p>
 * <ul>
 * <li>{@link EncryptionPort} — Base64 "encryption" (NOT secure; production
 * must use a KMS-managed envelope encryption adapter).</li>
 * <li>{@link CompatibilityTestPort} — always passes the compatibility test
 * (production must invoke the target Provider in a test environment).</li>
 * </ul>
 *
 * <p>Both stubs are fail-fast guarded: when {@code gateway.environment} is
 * {@code production} the application refuses to start instead of silently
 * degrading, mirroring {@link StubAuthConfiguration}'s guard. This prevents
 * an accidental production deployment from running on insecure stubs.</p>
 *
 * @since 0.1.0
 */
@Configuration
public class StubAdaptersConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StubAdaptersConfiguration.class);

    public StubAdaptersConfiguration(GatewayProperties properties) {
        assertStubAllowed(properties.getEnvironment());
    }

    static void assertStubAllowed(String environment) {
        if ("production".equalsIgnoreCase(environment)) {
            throw new IllegalStateException(
                    "Inline stub adapters (Base64 EncryptionPort / always-pass "
                            + "CompatibilityTestPort) are forbidden in production; "
                            + "implement real KMS and Provider compatibility-test adapters");
        }
    }

    /**
     * Stub {@link EncryptionPort} for development and testing.
     *
     * <p>Uses Base64 encoding — it is NOT secure and must be replaced with a
     * real KMS adapter (e.g., AWS KMS, HashiCorp Vault Transit) before
     * production deployment.</p>
     */
    @Bean
    public EncryptionPort encryptionPort() {
        return new EncryptionPort() {
            private final Base64.Encoder encoder = Base64.getEncoder();
            private final Base64.Decoder decoder = Base64.getDecoder();

            @Override
            public String encrypt(String plaintext) {
                if (plaintext == null) {
                    throw new IllegalArgumentException("plaintext must not be null");
                }
                return encoder.encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public String decrypt(String ciphertext) {
                if (ciphertext == null) {
                    throw new IllegalArgumentException("ciphertext must not be null");
                }
                byte[] decoded = decoder.decode(ciphertext);
                return new String(decoded, StandardCharsets.UTF_8);
            }
        };
    }

    /**
     * Stub {@link CompatibilityTestPort} that returns success.
     *
     * <p>Production should invoke the target Provider in the test environment.
     * This stub always returns a valid report with no errors or warnings.</p>
     */
    @Bean
    public CompatibilityTestPort compatibilityTestPort() {
        return (manifest, testEnvironment) -> {
            log.info("Compatibility test (stub): capabilityId={}, version={}, env={}",
                    manifest != null ? manifest.metadata().id() : "null",
                    manifest != null ? manifest.metadata().version() : "null",
                    testEnvironment);
            return ValidationReport.success();
        };
    }
}
