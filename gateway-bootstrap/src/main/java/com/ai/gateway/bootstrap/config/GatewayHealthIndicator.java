package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.SecretManager;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用于网关就绪检查的自定义健康指示器。
 *
 * <p>该指示器以 {@code gateway-readiness} 组件名参与
 * {@code /actuator/health} 端点，执行四项检查：</p>
 * <ol>
 * <li><b>数据库连接性</b> — 验证能否从 {@link DataSource} 获取 JDBC 连接。</li>
 * <li><b>已加载活动快照</b> — 验证 {@link CatalogPort} 能否加载当前生产快照。</li>
 * <li><b>必要密钥存在</b> — 验证关键密钥（如数据库凭据、LLM API Key）
 * 可通过 {@link SecretManager} 解析。</li>
 * <li><b>适配器初始化</b> — 验证关键适配器 Bean（目录端口、密钥管理器）
 * 已初始化且非 null。</li>
 * </ol>
 *
 * <p>若任意检查失败，整体状态为 {@code DOWN}，并在详情中指明失败项。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Component("gateway-readiness")
public class GatewayHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(GatewayHealthIndicator.class);

    private final DataSource dataSource;
    private final CatalogPort catalogPort;
    private final String environment;
    private final SecretManager secretManager;
    private final InMemoryCatalogManager catalogManager;

    /**
     * 构造一个新的 GatewayHealthIndicator。
     *
     * @param dataSource 用于数据库连接性检查的 JDBC 数据源
     * @param catalogPort 用于快照检查的目录端口
     * @param secretManager 用于密钥存在性检查的密钥管理器
     * @param environment 当前网关运行环境（如 "production"）
     */
    @Autowired
    public GatewayHealthIndicator(DataSource dataSource,
                                  CatalogPort catalogPort,
                                  SecretManager secretManager,
                                  GatewayProperties properties,
                                  InMemoryCatalogManager catalogManager) {
        this.dataSource = dataSource;
        this.catalogPort = catalogPort;
        this.secretManager = secretManager;
        this.environment = properties.getEnvironment();
        this.catalogManager = catalogManager;
        log.info("GatewayHealthIndicator initialized: environment={}", environment);
    }

    GatewayHealthIndicator(DataSource dataSource, CatalogPort catalogPort,
                           SecretManager secretManager, GatewayProperties properties) {
        this(dataSource, catalogPort, secretManager, properties, null);
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean allHealthy = true;

        // 检查一：数据库连接性
        boolean dbOk = checkDatabase(details);
        if (!dbOk) {
            allHealthy = false;
        }

        // 检查二：已加载活动快照
        boolean snapshotOk = checkSnapshot(details);
        if (!snapshotOk) {
            allHealthy = false;
        }

        // 检查三：必要密钥存在
        boolean secretsOk = checkSecrets(details);
        if (!secretsOk) {
            allHealthy = false;
        }

        // 检查四：适配器初始化状态
        boolean adaptersOk = checkAdapters(details);
        if (!adaptersOk) {
            allHealthy = false;
        }

        if (allHealthy) {
            return Health.up()
                    .withDetails(details)
                    .build();
        } else {
            return Health.down()
                    .withDetails(details)
                    .build();
        }
    }

    /**
     * 通过获取 JDBC 连接检查数据库连接性。
     *
     * @param details 用于填充检查结果的详情映射
     * @return 数据库可达时为 {@code true}
     */
    private boolean checkDatabase(Map<String, Object> details) {
        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(5);
            details.put("database", valid ? "UP" : "DOWN");
            if (!valid) {
                log.warn("Health check: database connection is not valid");
            }
            return valid;
        } catch (Exception e) {
            details.put("database", "DOWN: " + e.getMessage());
            log.warn("Health check: database connectivity failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查当前目录快照是否已加载。
     *
     * @param details 用于填充检查结果的详情映射
     * @return 快照已加载时为 {@code true}
     */
    private boolean checkSnapshot(Map<String, Object> details) {
        try {
            var snapshot = catalogPort.loadCurrentSnapshot(environment);
            var activated = catalogManager == null ? snapshot : catalogManager.getCurrentSnapshot();
            if (snapshot != null && snapshot.snapshotVersion() > 0
                    && activated != null && activated.snapshotVersion() == snapshot.snapshotVersion()
                    && environment.equals(activated.environment())) {
                details.put("snapshot", "UP (version=" + snapshot.snapshotVersion() + ")");
                return true;
            } else {
                details.put("snapshot", "DOWN (no active snapshot)");
                log.warn("Health check: no active catalog snapshot for environment {}", environment);
                return false;
            }
        } catch (Exception e) {
            details.put("snapshot", "DOWN: " + e.getMessage());
            log.warn("Health check: snapshot loading failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查必要的密钥是否齐备。
     *
     * @param details 用于填充检查结果的详情映射
     * @return 所有必要密钥均可解析时为 {@code true}
     */
    private boolean checkSecrets(Map<String, Object> details) {
        String[] requiredSecrets = {"DB_PASSWORD", "LLM_API_KEY"};
        boolean allPresent = true;
        Map<String, String> secretStatus = new LinkedHashMap<>();

        for (String secretKey : requiredSecrets) {
            try {
                secretManager.getSecret(secretKey);
                secretStatus.put(secretKey, "present");
            } catch (Exception e) {
                secretStatus.put(secretKey, "missing");
                allPresent = false;
                log.warn("Health check: secret '{}' not found: {}", secretKey, e.getMessage());
            }
        }

        details.put("secrets", secretStatus);
        return allPresent;
    }

    /**
     * 检查适配器 Bean 是否已初始化。
     *
     * @param details 用于填充检查结果的详情映射
     * @return 所有适配器均已初始化时为 {@code true}
     */
    private boolean checkAdapters(Map<String, Object> details) {
        boolean catalogOk = catalogPort != null;
        boolean secretOk = secretManager != null;

        Map<String, String> adapterStatus = new LinkedHashMap<>();
        adapterStatus.put("catalogPort", catalogOk ? "initialized" : "null");
        adapterStatus.put("secretManager", secretOk ? "initialized" : "null");

        details.put("adapters", adapterStatus);
        return catalogOk && secretOk;
    }
}
