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
    private A2a a2a = new A2a();
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
        /**
         * 远端 Agent 端点表（{@code 引用键 -> A2A JSON-RPC 地址}）。
         *
         * <p>与 {@code restEndpoints} 同一用途：清单只携带引用键，地址由部署侧决定。
         * 因此一份能力清单在各环境可以逐字节相同，而「导入清单」永远不可能给网关
         * 新增一个它本来到不了的出站目标。</p>
         */
        private java.util.Map<String, String> a2aAgentEndpoints = new java.util.LinkedHashMap<>();
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

    /**
     * A2A 互操作配置根节点（{@code gateway.a2a.*}）。
     *
     * <p>与 {@link NlRouter} 同样保持哑对象：模式字符串不在此处解析，
     * 由 {@code A2aMode}、{@code A2aSelectionMode}、{@code A2aIdentityMode} 各自的
     * {@code from(String)} 做失效关闭归一化。配置层一旦自己判定一次，
     * 就会出现「配置认为放行、适配器认为拒绝」这类只在生产才暴露的分歧。</p>
     */
    @Getter
    @Setter
    public static class A2a {
        /** 总开关；关闭时三个端点一律不注册。 */
        private boolean enabled;
        /** 承载模式：DISABLED | SERVER_ONLY | CLIENT_ONLY | FULL。 */
        private String mode = "DISABLED";
        /** 公开卡上的 Agent 名称。 */
        private String agentName = "capability-gateway";
        /** 公开卡上对外可达的基地址；{@code mode != DISABLED} 时必填。 */
        private String publicUrl = "";
        /** 是否强制扩展卡走认证；生产环境不允许置为 {@code false}。 */
        private boolean extendedCardRequired = true;
        /** 首跳选择模式：DELEGATED_SELECTION | GATEWAY_SELECTION | STRUCTURED_ONLY。 */
        private String selectionMode = "DELEGATED_SELECTION";
        /**
         * 生产环境显式放行 {@code GATEWAY_SELECTION} 档。
         *
         * <p>该档把「选哪个能力」交给网关侧的模型推理，因此入站流量会消耗模型额度、
         * 并把一条依赖模型可用性的路径放进生产。默认不放行，使这个代价必须被显式承担
         * 而不是从兼容默认值里继承下来。</p>
         */
        private boolean allowGatewaySelectionInProduction;
        /** 委托深度上限，超出即拒绝，用于阻断 Agent 间环路。 */
        private int maxDelegationDepth = 3;
        /** 未注册 peer 的默认身份来源模式：ON_BEHALF_OF | SERVICE_ACCOUNT。 */
        private String identityMode = "ON_BEHALF_OF";
        /** 入站 Task 的 QPS 上限。 */
        private double taskQps = 100d;
        /** 公开卡的独立 QPS 上限：匿名可达的端点必须与 Task 分开限流。 */
        private double cardQps = 50d;
        /** 出站客户端的并发上限。 */
        private int clientMaxConcurrency = 32;
        /** 首跳回传的候选数量上限。 */
        private int candidateTopK = 5;
        /** 执行时使用的语言标签。 */
        private String locale = "zh-CN";
        /** 执行时使用的时区标识；仅 {@code GATEWAY_SELECTION} 档用到。 */
        private String timezone = "UTC";
        /** 受信 peer 档案；只登记凭据指纹，未命中者恒为 {@code READ_ONLY}。 */
        private java.util.List<A2aPeerTrust> peerTrust = new java.util.ArrayList<>();
    }

    /**
     * 单个受信 peer 的档案配置（{@code gateway.a2a.peer-trust[]}）。
     *
     * <p>只登记 {@code tokenFingerprint}，不登记明文：注册表持有明文等于把一份可直接冒充
     * 受信 peer 的凭据副本留在配置里，而校验只需要指纹。</p>
     */
    @Getter
    @Setter
    public static class A2aPeerTrust {
        private String peerId = "";
        private String tokenFingerprint = "";
        /** 信任分级：READ_ONLY | TRUSTED_CONFIRMATION。 */
        private String trustTier = "READ_ONLY";
        /** 身份来源模式：ON_BEHALF_OF | SERVICE_ACCOUNT。 */
        private String identityMode = "ON_BEHALF_OF";
        /** 服务账号模式下的固定租户号；空表示未配置。 */
        private String serviceAccountOrgId = "";
        /** 服务账号模式下的能力白名单；该模式下不得为空。 */
        private java.util.List<String> allowedCapabilityIds = new java.util.ArrayList<>();
        private int maxDelegationDepth = 3;
        private boolean enabled = true;
        /** 过期时刻（ISO-8601）；空表示不过期。 */
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
