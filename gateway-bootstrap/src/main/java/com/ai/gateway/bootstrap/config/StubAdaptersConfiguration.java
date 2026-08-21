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
 * 针对尚无专属适配器实现的能力端口的内联桩适配器。
 *
 * <p>这些桩遵循规范的初始发布降级规则，且<b>仅用于开发</b>：</p>
 * <ul>
 * <li>{@link EncryptionPort} — Base64 “加密”（不安全；生产必须使用 KMS
 * 托管的信封加密适配器）。</li>
 * <li>{@link CompatibilityTestPort} — 始终通过兼容性测试
 * （生产必须在测试环境中调用目标 Provider）。</li>
 * </ul>
 *
 * <p>两个桩均受快速失败保护：当 {@code gateway.environment} 为
 * {@code production} 时，应用拒绝启动而非静默降级，与
 * {@link StubAuthConfiguration} 的保护一致。这可避免生产环境意外运行在
 * 不安全的桩上。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
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
     * 用于开发与测试的桩 {@link EncryptionPort}。
     *
     * <p>使用 Base64 编码——它并不安全，生产部署前必须替换为真实的 KMS
     * 适配器（如 AWS KMS、HashiCorp Vault Transit）。</p>
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
     * 始终返回成功的桩 {@link CompatibilityTestPort}。
     *
     * <p>生产环境应在测试环境中调用目标 Provider。该桩始终返回一个无错误、
     * 无警告的有效报告。</p>
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
