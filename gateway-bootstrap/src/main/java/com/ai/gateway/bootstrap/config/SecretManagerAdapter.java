package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.port.SecretManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实现 {@link SecretManager} 的引导适配器。
 *
 * <p>该适配器分两层解析密钥：</p>
 * <ol>
 * <li><b>环境变量</b> — 优先检查。键名会被转换为大写并使用下划线连接
 * （例如 {@code "db.password"} 转换为 {@code "DB_PASSWORD"}）。</li>
 * <li><b>密钥文件</b> — 若环境变量未设置，则读取位于
 * {@code gateway.secret-file-path}（如已配置）的 properties 文件，
 * 文件采用 {@code key=value} 格式。</li>
 * </ol>
 *
 * <p>生产环境应使用专用的密钥管理服务（如 HashiCorp Vault、Kubernetes
 * Secrets）或工作负载身份认证。配置文件与清单中绝不可包含明文密钥。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Component
public class SecretManagerAdapter implements SecretManager {

    private static final Logger log = LoggerFactory.getLogger(SecretManagerAdapter.class);

    private final Path secretFilePath;
    private final Map<String, String> fileSecrets;

    /**
     * 构造一个新的 SecretManagerAdapter。
     *
     * @param secretFilePath 密钥 properties 文件路径，解析自
     * {@code gateway.secret-file-path}；若未配置文件则为空或 {@code null}
     */
    public SecretManagerAdapter(GatewayProperties properties) {
        String secretFilePath = properties.getSecretFilePath();
        this.secretFilePath = (secretFilePath != null && !secretFilePath.isBlank())
                ? Paths.get(secretFilePath) : null;
        this.fileSecrets = loadSecretFile();
        log.info("SecretManagerAdapter initialized: secretFile={}",
                this.secretFilePath != null ? this.secretFilePath : "<none>");
    }

    @Override
    public String getSecret(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("secret key must not be null or blank");
        }

        // 第一层：环境变量
        String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        // 同时尝试以原始键名作为环境变量
        envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        // 第二层：密钥文件
        String fileValue = fileSecrets.get(key);
        if (fileValue != null && !fileValue.isBlank()) {
            return fileValue;
        }

        throw new IllegalStateException(
                "Secret not found for key: " + key + " (checked env var " + envKey
                        + " and secret file " + secretFilePath + ")");
    }

    /**
     * 将密钥 properties 文件加载为映射。
     *
     * <p>若文件不存在或无法读取，则返回空映射并记录警告。这样即使没有
     * 密钥文件，网关也能仅依赖环境变量正常启动。</p>
     *
     * @return 不可变的密钥键值对映射
     */
    private Map<String, String> loadSecretFile() {
        if (secretFilePath == null) {
            return Map.of();
        }

        if (!Files.exists(secretFilePath)) {
            log.warn("Secret file not found: {}", secretFilePath);
            return Map.of();
        }

        try {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(secretFilePath)) {
                properties.load(reader);
            }

            Map<String, String> secrets = new ConcurrentHashMap<>();
            for (String name : properties.stringPropertyNames()) {
                secrets.put(name, properties.getProperty(name));
            }

            log.info("Loaded {} secrets from file: {}", secrets.size(), secretFilePath);
            return Map.copyOf(secrets);

        } catch (IOException e) {
            log.error("Failed to load secret file: {} — {}", secretFilePath, e.getMessage(), e);
            return Map.of();
        }
    }
}
