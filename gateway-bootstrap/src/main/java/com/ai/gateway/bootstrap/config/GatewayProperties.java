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
    private Runtime runtime = new Runtime();

    /** 运行面配置根节点（{@code gateway.runtime.*}）。 */
    @Getter
    @Setter
    public static class Runtime {
        private NlRouter nlRouter = new NlRouter();
    }

    /**
     * 运行面自然语言路由的曝光配置（{@code gateway.runtime.nl-router.*}）。
     *
     * <p>此处只承载「原始配置文本」，不做语义判定：模式解析与非法组合归一化由
     * {@code NlRouterMode} 与 {@code NlRouterPolicy} 负责，配置类保持哑对象，
     * 从而使同一份判定被运行面与管理面诊断面共用，不在配置层出现第二套 switch。</p>
     */
    @Getter
    @Setter
    public static class NlRouter {
        /** 运行面模式：FULL | COMPAT | DIAGNOSTIC | DISABLED。 */
        private String mode = "COMPAT";
        /** 管理面诊断端点开关；仅在模式本身支持诊断时生效。 */
        private boolean diagnosticsEnabled = true;
        /** 单次诊断输出的候选上限，防止一次调用倾泻整个目录。 */
        private int diagnosticsMaxCandidates = 10;
        /** 诊断端点 QPS 上限；诊断会真实消耗模型额度，必须独立限流。 */
        private int diagnosticsQps = 5;
    }

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
        /** {@code tools/list} 曝光模式：{@code META_TOOL} | {@code DIRECT_PROJECTION} | {@code HYBRID}。 */
        private String mcpToolExposure = "HYBRID";
        /** 单次 {@code tools/list} 最多直投的工具数，超出按排序策略取前 N 个并降级。 */
        private int mcpDirectMaxTools = 64;
        /** 单次 {@code tools/list} 所有直投 Schema 的累计字节上限。 */
        private long mcpDirectMaxSchemaBytes = 131_072L;
        /** 目录/策略纪元的观测间隔，变化时推送 {@code notifications/tools/list_changed}。 */
        private long mcpToolListWatchMs = 5_000L;
        private double mcpNotifyQps = 20d;
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
