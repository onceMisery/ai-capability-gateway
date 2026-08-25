package com.ai.gateway.bootstrap.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关非敏感运行时配置的强类型载体。
 * 各组件应直接使用该对象，而不是重复声明兜底默认值。
 *
 * @author cmiracle@163.com
 */
@ConfigurationProperties(prefix = "gateway")
@Getter
@Setter
public class GatewayProperties {

    /** 运行环境标识（如 dev / test / prod）。 */
    private String environment = "";
    /** 请求体最大字节数。 */
    private int maxRequestSizeBytes = 65_536;
    /** 响应体最大字节数。 */
    private long maxResponseBytes = 1_048_576L;
    /** 默认调用超时时间（毫秒）。 */
    private int defaultTimeoutMs = 15_000;
    /** 密钥文件路径。 */
    private String secretFilePath = "";
    private Provider auth = new Provider();
    private Provider cache = new Provider();
    private Provider ratelimit = new Provider();
    private Llm llm = new Llm();
    private Operation operation = new Operation();
    private Agent agent = new Agent();
    private Redis redis = new Redis();
    private Audit audit = new Audit();
    private Snapshot snapshot = new Snapshot();
    private Sentinel sentinel = new Sentinel();
    private Protocol protocol = new Protocol();

    @Getter
    @Setter
    public static class Protocol {
        private java.util.Map<String, String> restEndpoints = new java.util.LinkedHashMap<>();
        private java.util.Map<String, String> grpcEndpoints = new java.util.LinkedHashMap<>();
        private java.util.Map<String, String> grpcDescriptorSets = new java.util.LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Provider {
        private String provider = "";
        private SaToken saToken = new SaToken();
        private ConsoleAdmin consoleAdmin = new ConsoleAdmin();
    }

    @Getter
    @Setter
    public static class SaToken {
        private String jwtSecretKey = "";
    }

    @Getter
    @Setter
    public static class ConsoleAdmin {
        private String username = "";
        private String password = "";
    }

    @Getter
    @Setter
    public static class Llm {
        private String endpoint = "";
        private String apiKey = "";
        private String model = "";
        private double temperature = 0.1d;
        private int maxTokens = 4096;
    }

    @Getter
    @Setter
    public static class Operation {
        private String confirmationSecret = "";
    }

    @Getter
    @Setter
    public static class Agent {
        private String toolRefCurrentKeyId = "k1";
        private String toolRefSecret = "";
        private String toolRefPreviousKeyId = "";
        private String toolRefPreviousSecret = "";
        private long toolRefTtlSeconds = 120L;
        private long resolveTimeoutMs = 100L;
        private int pendingConfirmationMaxEntries = 10_000;
        private int turnMaxEntries = 10_000;
        private int resolveMaxConcurrent = 64;
        private int resolveMaxQueue = 64;
        private int catalogMaxCapabilities = 10_000;
        private long catalogMaxIndexBytes = 67_108_864L;
        private long catalogMaxProcessMemoryBytes = 536_870_912L;
        private long catalogBuildTimeoutMs = 5_000L;
        private long catalogLeaseHoldTimeoutMs = 1_000L;
        private int catalogIoMaxRows = 10_000;
        private long catalogIoQueryTimeoutMs = 3_000L;
        private long catalogIoMaxPayloadBytes = 134_217_728L;
        private int mcpMaxSessions = 1_000;
        private long mcpSessionIdleSeconds = 1_800L;
        private String mcpSecurityMode = "READ_ONLY";
        private long mcpCallTimeoutMs = 30_000L;
        private int mcpCallMaxConcurrent = 64;
        private int mcpCallMaxQueue = 128;
        private long mcpCloseTimeoutMs = 5_000L;
        private String mcpNodeId = "local";
        private double mcpSseQps = 100d;
        private double mcpMessageQps = 500d;
        private double mcpResolveQps = 200d;
        private double mcpCallQps = 200d;
        private java.util.List<McpTrustedClient> mcpTrustedClients =
                new java.util.ArrayList<>();
    }

    @Getter
    @Setter
    public static class McpTrustedClient {
        private String clientId = "";
        private String tokenFingerprint = "";
        private String tokenAssurance = "HIGH";
        private String confirmationChannel = "HOST_UI";
        private boolean enabled = true;
        private String expiresAt = "";
    }

    @Getter
    @Setter
    public static class Redis {
        private String address = "";
        private String password = "";
        private int database;
        private SnapshotCache snapshot = new SnapshotCache();
    }

    @Getter
    @Setter
    public static class SnapshotCache {
        private int localTtlSeconds = 30;
    }

    @Getter
    @Setter
    public static class Audit {
        private int batchSize = 50;
        private int batchWaitMillis = 5;
    }

    @Getter
    @Setter
    public static class Snapshot {
        private long maxLagMillis = 30_000L;
    }

    @Getter
    @Setter
    public static class Sentinel {
        private double globalQps = 2000d;
        private double llmQps = 20d;
        private int llmMaxQueueingMs = 2000;
    }
}
