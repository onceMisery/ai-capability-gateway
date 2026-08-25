package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.llm.HttpLlmRouterAdapter;
import com.ai.gateway.adapter.llm.LlmRequestBuilder;
import com.ai.gateway.adapter.llm.LlmResponseParser;
import com.ai.gateway.adapter.llm.PromptTemplateRegistry;
import com.ai.gateway.application.agent.AgentCapabilityResolver;
import com.ai.gateway.application.agent.AgentHostToolCallUseCase;
import com.ai.gateway.application.agent.AgentModelResultMapper;
import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentResolveAdmissionController;
import com.ai.gateway.application.agent.AgentTurnStore;
import com.ai.gateway.application.agent.AgentToolProjectionUseCase;
import com.ai.gateway.application.agent.CapabilityProjectionRanker;
import com.ai.gateway.application.agent.InMemoryAgentTurnStore;
import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
import com.ai.gateway.application.agent.InMemoryPendingConfirmationStore;
import com.ai.gateway.application.agent.PendingConfirmationStore;
import com.ai.gateway.application.agent.ToolReferenceService;
import com.ai.gateway.adapter.mcp.McpGatewayAdapter;
import com.ai.gateway.adapter.mcp.McpClientTrustProfile;
import com.ai.gateway.adapter.mcp.McpClientTrustRegistry;
import com.ai.gateway.adapter.mcp.McpRequestContextFilter;
import com.ai.gateway.adapter.mcp.McpRateLimiter;
import com.ai.gateway.adapter.mcp.McpSecurityMode;
import com.ai.gateway.adapter.mcp.McpWebMvcTransportAdapter;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.application.catalog.CandidateResolutionService;
import com.ai.gateway.application.catalog.LuceneCandidateRetriever;
import com.ai.gateway.application.console.AclManageUseCase;
import com.ai.gateway.application.console.AuditQueryUseCase;
import com.ai.gateway.application.console.CapabilityQueryUseCase;
import com.ai.gateway.application.console.ConfigQueryUseCase;
import com.ai.gateway.application.console.ConsoleAuthUseCase;
import com.ai.gateway.application.console.StatsQueryUseCase;
import com.ai.gateway.application.controlplane.CapabilitySuspendUseCase;
import com.ai.gateway.application.controlplane.CapabilityResumeUseCase;
import com.ai.gateway.application.controlplane.CatalogPublishUseCase;
import com.ai.gateway.application.controlplane.CatalogRollbackUseCase;
import com.ai.gateway.domain.model.CatalogEnvironment;
import com.ai.gateway.application.controlplane.ManifestApprovalUseCase;
import com.ai.gateway.application.controlplane.ManifestImportUseCase;
import com.ai.gateway.application.controlplane.ManifestValidationUseCase;
import com.ai.gateway.application.controlplane.CatalogSnapshotQueryUseCase;
import com.ai.gateway.application.operation.OperationConfirmUseCase;
import com.ai.gateway.application.operation.OperationCancelUseCase;
import com.ai.gateway.application.operation.OperationPrepareUseCase;
import com.ai.gateway.application.operation.OperationStatusUseCase;
import com.ai.gateway.application.resilience.BulkheadManager;
import com.ai.gateway.application.resilience.CircuitBreakerManager;
import com.ai.gateway.application.resilience.FaultHandler;
import com.ai.gateway.application.resilience.RateLimiterManager;
import com.ai.gateway.application.resilience.ResilientInvocationAdapter;
import com.ai.gateway.application.resilience.ResilientLlmRouter;
import com.ai.gateway.bootstrap.telemetry.MicrometerTelemetryAdapter;
import com.ai.gateway.application.runtime.ClarificationUseCase;
import com.ai.gateway.application.runtime.AgentToolCallUseCase;
import com.ai.gateway.application.runtime.AgentToolCatalogUseCase;
import com.ai.gateway.application.runtime.DeterministicExecutionUseCase;
import com.ai.gateway.application.runtime.DefaultSelectDecisionProcessor;
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.application.runtime.NlRouteDiagnosticsUseCase;
import com.ai.gateway.application.runtime.NlRouterPolicy;
import com.ai.gateway.application.runtime.SelectDecisionProcessor;
import com.ai.gateway.application.runtime.StructuredInvocationUseCase;
import com.ai.gateway.application.runtime.HealthReadinessUseCase;
import com.ai.gateway.domain.model.CacheStatus;
import com.ai.gateway.domain.model.NlRouterMode;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ConverterType;
import com.ai.gateway.domain.model.GatewayConfig;
import com.ai.gateway.domain.model.PayloadLimits;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AclRepository;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.AuditQueryPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.ArgumentPayloadCodec;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.CompatibilityTestPort;
import com.ai.gateway.domain.port.ConfirmationTokenCodec;
import com.ai.gateway.domain.port.EncryptionPort;
import com.ai.gateway.domain.port.EnvelopeProfileRegistry;
import com.ai.gateway.domain.port.InteractionRepository;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.OperationRepository;
import com.ai.gateway.domain.port.RateLimiterPort;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.StatsQueryPort;
import com.ai.gateway.domain.port.SnapshotNotifier;
import com.ai.gateway.domain.port.TokenIssuerPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.port.TransactionPort;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import com.ai.gateway.domain.service.AliasGenerator;
import com.ai.gateway.domain.service.DeadlineBudgetManager;
import com.ai.gateway.domain.service.LifecycleStateMachine;
import com.ai.gateway.domain.service.ManifestValidator;
import com.ai.gateway.domain.service.OperationStateMachine;
import com.ai.gateway.domain.service.RedactionService;
import com.ai.gateway.domain.service.TextNormalizer;
import com.ai.gateway.domain.service.ThresholdEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.gateway.adapter.dubbo.DubboInvocationAdapter;
import com.ai.gateway.adapter.rest.RestInvocationAdapter;
import com.ai.gateway.adapter.grpc.GrpcInvocationAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI 能力网关的手动 Bean 装配。
 *
 * <p>这是适配器实现与应用用例装配的<b>唯一场所</b>。适配器 Bean 由 Spring
 * 的依赖解析注入到用例构造函数中——不使用字段注入，也不使用 {@code @Autowired}。</p>
 *
 * <p>拥有专属适配器实现的端口接口（如 {@link CatalogPort}、{@link ManifestRepository}）
 * 通过主应用类上的 {@code @Import} 引入，并在此作为构造函数参数被解析。</p>
 *
 * <p>没有专属适配器的端口接口（如 {@link AuthenticationPort}、{@link EncryptionPort}）
 * 在本类中接收内联桩实现。这些桩遵循规范的初始发布降级规则：
 * 授权为可选项、加密使用 Base64（仅开发）等。</p>
 *
 * <p><b>ArgumentBinder</b> 与 <b>ResultNormalizer</b> 是按需请求的领域服务，
 * 需要运行时上下文（Principal、SystemContext、CapabilityManifest、OutputContract）。
 * 它们由用例在每次请求时创建，没有单例 {@code @Bean} 定义。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
public class BeanConfig {

    /**
     * 创建全局 Payload 预算，供所有执行入口共享。
     *
     * @param gatewayProperties 请求/响应字节上限
     * @param payloadProperties JSON 结构上限
     * @return 统一 Payload 预算
     */
    @Bean
    public PayloadLimits payloadLimits(GatewayProperties gatewayProperties,
                                       PayloadLimitsProperties payloadProperties) {
        return new PayloadLimits(
                gatewayProperties.getMaxRequestSizeBytes(),
                gatewayProperties.getMaxResponseBytes(),
                payloadProperties.getMaxJsonDepth(),
                payloadProperties.getMaxArrayLength(),
                payloadProperties.getMaxObjectFields(),
                payloadProperties.getMaxStringBytes(),
                payloadProperties.getMaxNodeCount());
    }

    private static final Logger log = LoggerFactory.getLogger(BeanConfig.class);

    // ======================================================================
    // 领域服务
    // ======================================================================

    /**
     * 用于稳定参数名解析的别名生成器。
     */
    @Bean
    public AliasGenerator aliasGenerator() {
        return new AliasGenerator();
    }

    /**
     * 用于敏感数据脱敏的编辑服务。
     */
    @Bean
    public RedactionService redactionService() {
        return new RedactionService();
    }

    /**
     * 执行 10 步校验流水线的清单校验器。
     */
    @Bean
    public ManifestValidator manifestValidator(
            SchemaValidator schemaValidator,
            CompatibilityTestPort compatibilityTestPort,
            CatalogPort catalogPort,
            EnvelopeProfileRegistry envelopeProfileRegistry,
            GatewayProperties gatewayProperties) {
        return new ManifestValidator(
                schemaValidator,
                compatibilityTestPort,
                catalogPort,
                envelopeProfileRegistry,
                CatalogEnvironment.DEFAULT);
    }

    /**
     * 用于清单状态流转的生命周期状态机。
     */
    @Bean
    public LifecycleStateMachine lifecycleStateMachine() {
        return new LifecycleStateMachine();
    }

    /**
     * 用于两阶段写操作的操作状态机。
     */
    @Bean
    public OperationStateMachine operationStateMachine() {
        return new OperationStateMachine();
    }

    /**
     * 用于查询预处理的文本归一化器。
     */
    @Bean
    public TextNormalizer textNormalizer() {
        return new TextNormalizer();
    }

    /**
     * 用于 LLM 决策置信度检查的阈值评估器。
     */
    @Bean
    public ThresholdEvaluator thresholdEvaluator() {
        return new ThresholdEvaluator();
    }

    /**
     * 用于端到端超时控制的截止时间预算管理器。
     */
    @Bean
    public DeadlineBudgetManager deadlineBudgetManager() {
        return new DeadlineBudgetManager();
    }

    /**
     * 在应用启动时加载最新目录快照，使内存目录立即可用于路由。
     */
    @Bean
    public org.springframework.boot.ApplicationRunner catalogStartupLoader(
            InMemoryCatalogManager catalogManager,
            com.ai.gateway.adapter.dubbo.DubboReferenceManager dubboReferenceManager,
            GatewayProperties gatewayProperties,
            @org.springframework.beans.factory.annotation.Value("${dubbo.registry.address:nacos://nacos.dev.com:8848}") String dubboRegistryAddress) {
        return args -> {
            // 为清单 registryRef 解析注册 Dubbo 注册中心地址
            dubboReferenceManager.registerRegistryAddress("nacos-main", dubboRegistryAddress);

            log.info("Loading catalog snapshot on startup...");
            boolean loaded = catalogManager.loadAndActivate(CatalogEnvironment.DEFAULT);
            if (loaded) {
                log.info("Catalog snapshot loaded successfully on startup: version={}",
                        catalogManager.getCurrentSnapshotVersion());
            } else {
                log.warn("No catalog snapshot available on startup (first run or no publish yet)");
            }
        };
    }

    // ======================================================================
    // 桩端口实现（无专属适配器的端口）
    // ======================================================================
    //
    // AuthenticationPort/AuthorizationPort 桩位于 StubAuthConfiguration
    // （条件为 gateway.auth.provider）。EncryptionPort/CompatibilityTestPort
    // 桩位于 StubAdaptersConfiguration，并带生产快速失败保护。

    /**
     * 实现三种内置受控类型转换器的 {@link TypeConverterRegistry}。
     *
     * <p>封闭的白名单包含：</p>
     * <ul>
     * <li>{@link ConverterType#ISO_DATE_TO_EPOCH_MILLIS}</li>
     * <li>{@link ConverterType#ENUM_UPPERCASE}</li>
     * <li>{@link ConverterType#STRING_TRIM}</li>
     * </ul>
     */
    @Bean
    public TypeConverterRegistry typeConverterRegistry() {
        return new TypeConverterRegistry() {
            @Override
            public Object convert(ConverterType converterType, Object sourceValue) {
                if (converterType == null) {
                    throw new IllegalArgumentException("converterType must not be null");
                }
                if (sourceValue == null) {
                    throw new IllegalArgumentException(
                            "sourceValue must not be null for converter: " + converterType);
                }
                return switch (converterType) {
                    case ISO_DATE_TO_EPOCH_MILLIS -> {
                        try {
                            Instant instant = Instant.parse(sourceValue.toString());
                            yield instant.toEpochMilli();
                        } catch (DateTimeParseException e) {
                            throw new IllegalArgumentException(
                                    "ISO_DATE_TO_EPOCH_MILLIS conversion failed: " + e.getMessage(), e);
                        }
                    }
                    case ENUM_UPPERCASE -> sourceValue.toString().toUpperCase();
                    case STRING_TRIM -> sourceValue.toString().trim();
                };
            }

            @Override
            public boolean isRegistered(ConverterType converterType) {
                return converterType != null;
            }
        };
    }

    // ======================================================================
    // LLM HTTP 适配器
    // ======================================================================

    /**
     * 基于 {@code application.yml} 中的配置值创建 {@link HttpLlmRouterAdapter}
     * 作为 {@link LlmRouterPort} Bean。
     *
     * <p>该适配器无法使用 {@code @Import} 引入，因为其构造函数需要非 Bean
     * 参数（端点 URL、API Key、模型名、温度、最大 Token 数），这些参数来自
     * 配置属性而非 Spring 自动装配。</p>
     */
    @Bean
    public LlmRouterPort llmRouterPort(
            GatewayProperties gatewayProperties,
            LlmRequestBuilder requestBuilder,
            LlmResponseParser responseParser,
            PromptTemplateRegistry templateRegistry,
            RateLimiterManager rateLimiterManager,
            CircuitBreakerManager circuitBreakerManager,
            BulkheadManager bulkheadManager,
            TelemetryPort telemetryPort) {
        LlmRouterPort raw = new HttpLlmRouterAdapter(
                gatewayProperties.getLlm().getEndpoint(),
                gatewayProperties.getLlm().getApiKey(),
                gatewayProperties.getLlm().getModel(),
                gatewayProperties.getLlm().getTemperature(),
                gatewayProperties.getLlm().getMaxTokens(),
                requestBuilder,
                responseParser,
                templateRegistry,
                (int) gatewayProperties.getMaxResponseBytes());
        return new ResilientLlmRouter(raw, rateLimiterManager, circuitBreakerManager,
                bulkheadManager, telemetryPort);
    }

    @Bean
    public TelemetryPort telemetryPort(ObservationRegistry observationRegistry,
                                       MeterRegistry meterRegistry) {
        return new MicrometerTelemetryAdapter(observationRegistry, meterRegistry);
    }

    /** 主运行期适配器；具体的 Dubbo Bean 仍保留以供诊断使用。 */
    @Bean
    @Primary
    public InvocationAdapter resilientInvocationAdapter(
            DubboInvocationAdapter delegate,
            RestInvocationAdapter restInvocationAdapter,
            GrpcInvocationAdapter grpcInvocationAdapter,
            ManifestRepository manifestRepository,
            RateLimiterManager rateLimiterManager,
            CircuitBreakerManager circuitBreakerManager,
            BulkheadManager bulkheadManager,
            TelemetryPort telemetryPort) {
        InvocationAdapter routed = ProtocolRoutingInvocationAdapter.of(
                manifestRepository, List.of(delegate, restInvocationAdapter, grpcInvocationAdapter));
        return new ResilientInvocationAdapter(routed, rateLimiterManager,
                circuitBreakerManager, bulkheadManager, telemetryPort);
    }

    // ======================================================================
    // 目录管理器
    // ======================================================================

    /**
     * 缓存活动快照并协调索引重建的内存目录管理器。
     */
    @Bean
    public InMemoryCatalogManager inMemoryCatalogManager(
            CatalogPort catalogPort,
            CapabilityPublicProjectionService projectionService,
            LuceneCandidateRetriever candidateRetriever,
            TelemetryPort telemetryPort,
            GatewayProperties gatewayProperties,
            @org.springframework.beans.factory.annotation.Qualifier("catalogRefreshExecutor")
            ExecutorService catalogRefreshExecutor) {
        GatewayProperties.Agent agent = gatewayProperties.getAgent();
        return new InMemoryCatalogManager(
                catalogPort, projectionService, candidateRetriever, telemetryPort,
                agent.getCatalogMaxCapabilities(), agent.getCatalogMaxIndexBytes(),
                agent.getCatalogMaxProcessMemoryBytes(), agent.getCatalogBuildTimeoutMs(),
                agent.getCatalogLeaseHoldTimeoutMs(),
                catalogRefreshExecutor);
    }

    /**
     * 基于 Lucene、使用 BM25 评分的候选检索器。
     */
    @Bean
    public LuceneCandidateRetriever luceneCandidateRetriever() {
        return new LuceneCandidateRetriever();
    }

    /**
     * 将数据库快照读取与 Lucene/视图构建从请求线程和 Redis 监听线程中隔离。
     * 单一工作线程还可避免重叠生成导致目录构建开销翻倍。
     */
    @Bean(name = "catalogRefreshExecutor", destroyMethod = "shutdown")
    public ExecutorService catalogRefreshExecutor() {
        return Executors.newFixedThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "gateway-catalog-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ======================================================================
    // 韧性管理器（第 18 节）
    // ======================================================================

    /**
     * 用于有限资源约束的限流管理器。
     */
    @Bean
    public RateLimiterManager rateLimiterManager(RateLimiterPort rateLimiterPort) {
        return new RateLimiterManager(rateLimiterPort);
    }

    /**
     * 用于 Provider/能力故障隔离的熔断器管理器。
     */
    @Bean
    public CircuitBreakerManager circuitBreakerManager() {
        return new CircuitBreakerManager();
    }

    /**
     * 用于按 Provider/能力进行并发隔离的舱壁管理器。
     */
    @Bean
    public BulkheadManager bulkheadManager() {
        return new BulkheadManager();
    }

    /**
     * 用于判定故障响应的故障处理器。
     */
    @Bean
    public FaultHandler faultHandler() {
        return new FaultHandler();
    }

    // ======================================================================
    // 用例 — 控制面（第 8 节）
    // ======================================================================

    /**
     * 清单导入用例 — 10 步校验流水线。
     */
    @Bean
    public ManifestImportUseCase manifestImportUseCase(
            ManifestRepository manifestRepository,
            ManifestValidator manifestValidator,
            SchemaValidator schemaValidator,
            CompatibilityTestPort compatibilityTestPort) {
        return new ManifestImportUseCase(
                manifestRepository,
                manifestValidator,
                schemaValidator,
                compatibilityTestPort);
    }

    @Bean
    public ManifestValidationUseCase manifestValidationUseCase(
            ManifestRepository manifestRepository,
            ManifestValidator manifestValidator) {
        return new ManifestValidationUseCase(manifestRepository, manifestValidator);
    }

    @Bean
    public CapabilityResumeUseCase capabilityResumeUseCase(
            ManifestRepository manifestRepository,
            ManifestValidationUseCase manifestValidationUseCase,
            LifecycleStateMachine lifecycleStateMachine) {
        return new CapabilityResumeUseCase(
                manifestRepository, manifestValidationUseCase, lifecycleStateMachine);
    }

    @Bean
    public CatalogSnapshotQueryUseCase catalogSnapshotQueryUseCase(CatalogPort catalogPort) {
        return new CatalogSnapshotQueryUseCase(catalogPort);
    }

    /**
     * 清单审批用例 — 生命周期状态流转。
     */
    @Bean
    public ManifestApprovalUseCase manifestApprovalUseCase(
            ManifestRepository manifestRepository,
            LifecycleStateMachine lifecycleStateMachine) {
        return new ManifestApprovalUseCase(
                manifestRepository,
                lifecycleStateMachine);
    }

    /**
     * 目录发布用例 — 单事务发布。
     */
    @Bean
    public CatalogPublishUseCase catalogPublishUseCase(
            ManifestRepository manifestRepository,
            CatalogPort catalogPort,
            SnapshotNotifier snapshotNotifier,
            LifecycleStateMachine lifecycleStateMachine,
            TransactionPort transactionPort,
            CapabilityPublicProjectionService publicProjectionService) {
        return new CatalogPublishUseCase(
                manifestRepository,
                catalogPort,
                snapshotNotifier,
                lifecycleStateMachine,
                transactionPort,
                publicProjectionService);
    }

    /**
     * 目录回滚用例 — 历史快照复制。
     */
    @Bean
    public CatalogRollbackUseCase catalogRollbackUseCase(
            CatalogPort catalogPort,
            SnapshotNotifier snapshotNotifier,
            TransactionPort transactionPort) {
        return new CatalogRollbackUseCase(
                catalogPort,
                snapshotNotifier,
                transactionPort);
    }

    /**
     * 能力停用用例 — 紧急停用。
     */
    @Bean
    public CapabilitySuspendUseCase capabilitySuspendUseCase(
            ManifestRepository manifestRepository,
            CatalogPort catalogPort,
            SnapshotNotifier snapshotNotifier,
            LifecycleStateMachine lifecycleStateMachine,
            GatewayProperties gatewayProperties,
            TransactionPort transactionPort) {
        return new CapabilitySuspendUseCase(manifestRepository, catalogPort, snapshotNotifier,
                CatalogEnvironment.DEFAULT, lifecycleStateMachine, transactionPort);
    }

    // ======================================================================
    // 用例 — 运行面（第 9 节）
    // ======================================================================

    /**
     * 自然语言查询用例 — 11 步路由流水线。
     */
    @Bean
    public SelectDecisionProcessor selectDecisionProcessor(
            AuthorizationPort authorizationPort,
            LlmRouterPort llmRouterPort,
            SchemaValidator schemaValidator,
            AliasGenerator aliasGenerator,
            TypeConverterRegistry typeConverterRegistry,
            DeterministicExecutionUseCase deterministicExecutionUseCase,
            PayloadLimits payloadLimits) {
        return new DefaultSelectDecisionProcessor(
                authorizationPort, llmRouterPort, schemaValidator, aliasGenerator,
                typeConverterRegistry, deterministicExecutionUseCase, payloadLimits);
    }

    /**
     * 运行面自然语言路由的曝光策略。
     *
     * <p>把配置文本在装配期一次性解析为不可变值对象：运行面用例与管理面诊断用例共享
     * 同一个实例，因此两者对「是否曝光 / 是否允许澄清会话 / 是否允许诊断」的判定
     * 天然一致，不存在两处各自 switch 模式枚举而漂移的可能。</p>
     */
    @Bean
    public NlRouterPolicy nlRouterPolicy(GatewayProperties gatewayProperties) {
        GatewayProperties.NlRouter config = gatewayProperties.getRuntime().getNlRouter();
        return new NlRouterPolicy(
                NlRouterMode.parse(config.getMode()),
                config.isDiagnosticsEnabled(),
                config.getDiagnosticsMaxCandidates() > 0
                        ? config.getDiagnosticsMaxCandidates()
                        : NlRouterPolicy.DEFAULT_DIAGNOSTICS_MAX_CANDIDATES);
    }

    /**
     * 候选能力确定性解析服务：运行面自然语言路由与管理面诊断面共用的唯一检索内核。
     *
     * <p>之所以必须是单一 Bean：该链路含授权前置过滤这一安全关键步骤，多个入口各自
     * 持有一套检索实现等同于持有多套授权过滤，任何一处遗漏都是越权泄漏。</p>
     */
    @Bean
    public CandidateResolutionService candidateResolutionService(
            CatalogPort catalogPort,
            AuthorizationPort authorizationPort,
            CandidateRetriever candidateRetriever,
            TextNormalizer textNormalizer) {
        return new CandidateResolutionService(catalogPort, authorizationPort,
                candidateRetriever, textNormalizer, CatalogEnvironment.DEFAULT);
    }

    /**
     * 管理面能力目录诊断用例（dry-run）。
     *
     * <p>与运行面用例共用 {@link CandidateResolutionService}、{@link ThresholdEvaluator}
     * 与 {@code RoutingThresholds.defaults()}，因此诊断结论可用于解释线上行为。</p>
     */
    @Bean
    public NlRouteDiagnosticsUseCase nlRouteDiagnosticsUseCase(
            CandidateResolutionService candidateResolutionService,
            CapabilityPublicProjectionService projectionService,
            AliasGenerator aliasGenerator,
            ThresholdEvaluator thresholdEvaluator,
            LlmRouterPort llmRouterPort,
            com.ai.gateway.domain.port.AuditPort auditPort,
            NlRouterPolicy nlRouterPolicy) {
        return new NlRouteDiagnosticsUseCase(candidateResolutionService, projectionService,
                aliasGenerator, thresholdEvaluator, llmRouterPort, auditPort, nlRouterPolicy);
    }

    @Bean
    public NaturalLanguageQueryUseCase naturalLanguageQueryUseCase(
            AuthenticationPort authenticationPort,
            CandidateResolutionService candidateResolutionService,
            com.ai.gateway.domain.port.AuditPort auditPort,
            ThresholdEvaluator thresholdEvaluator,
            InteractionRepository interactionRepository,
            SelectDecisionProcessor selectDecisionProcessor,
            NlRouterPolicy nlRouterPolicy) {
        return new NaturalLanguageQueryUseCase(
                authenticationPort,
                candidateResolutionService,
                auditPort,
                thresholdEvaluator,
                interactionRepository,
                selectDecisionProcessor,
                nlRouterPolicy);
    }

    /**
     * 确定性执行用例 — 带结果归一化的 Provider 调用（第 11 节）。
     */
    @Bean
    public DeterministicExecutionUseCase deterministicExecutionUseCase(
            InvocationAdapter invocationAdapter,
            TypeConverterRegistry typeConverterRegistry,
            RedactionService redactionService,
            SchemaValidator schemaValidator,
            AuthorizationPort authorizationPort,
            com.ai.gateway.domain.port.AuditPort auditPort,
            DeadlineBudgetManager deadlineBudgetManager,
            PayloadLimits payloadLimits) {
        return new DeterministicExecutionUseCase(
                invocationAdapter,
                typeConverterRegistry,
                redactionService,
                schemaValidator,
                authorizationPort,
                auditPort,
                deadlineBudgetManager,
                payloadLimits);
    }

    /** 结构化工具调用与 NL 查询共享同一确定性执行内核。 */
    @Bean
    public StructuredInvocationUseCase structuredInvocationUseCase(
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            CatalogPort catalogPort,
            SchemaValidator schemaValidator,
            TypeConverterRegistry typeConverterRegistry,
            com.ai.gateway.domain.port.AuditPort auditPort,
            DeterministicExecutionUseCase deterministicExecutionUseCase,
            GatewayProperties gatewayProperties,
            PayloadLimits payloadLimits) {
        return new StructuredInvocationUseCase(authenticationPort, authorizationPort,
                catalogPort, schemaValidator, typeConverterRegistry, auditPort,
                deterministicExecutionUseCase, CatalogEnvironment.DEFAULT,
                payloadLimits);
    }

    /** 面向 Agent 的发现仅在上下文中保留少量已授权的 Top-K。 */
    @Bean
    public AgentToolCatalogUseCase agentToolCatalogUseCase(
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            InMemoryCatalogManager catalogManager,
            CandidateRetriever candidateRetriever,
            AliasGenerator aliasGenerator,
            GatewayProperties gatewayProperties) {
        return new AgentToolCatalogUseCase(authenticationPort, authorizationPort,
                catalogManager, candidateRetriever, new TextNormalizer(), aliasGenerator,
                CatalogEnvironment.DEFAULT);
    }

    /** 统一的 Agent 读取/准备分发器。 */
    @Bean
    public AgentToolCallUseCase agentToolCallUseCase(
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            CatalogPort catalogPort,
            StructuredInvocationUseCase structuredInvocationUseCase,
            OperationPrepareUseCase operationPrepareUseCase,
            GatewayProperties gatewayProperties) {
        return new AgentToolCallUseCase(authenticationPort, authorizationPort, catalogPort,
                structuredInvocationUseCase, operationPrepareUseCase,
                CatalogEnvironment.DEFAULT);
    }

    @Bean
    public CapabilityPublicProjectionService capabilityPublicProjectionService() {
        return new CapabilityPublicProjectionService();
    }

    @Bean
    public ToolReferenceService toolReferenceService(GatewayProperties gatewayProperties) {
        GatewayProperties.Agent agent = gatewayProperties.getAgent();
        byte[] currentKey = toolReferenceKey(
                agent.getToolRefSecret(), gatewayProperties.getEnvironment());
        byte[] previousKey = agent.getToolRefPreviousKeyId() == null
                || agent.getToolRefPreviousKeyId().isBlank()
                ? null
                : toolReferenceKey(agent.getToolRefPreviousSecret(),
                        gatewayProperties.getEnvironment());
        return new ToolReferenceService(
                agent.getToolRefCurrentKeyId(), currentKey,
                agent.getToolRefPreviousKeyId(), previousKey,
                agent.getToolRefTtlSeconds());
    }

    @Bean(name = "agentResolveExecutor", destroyMethod = "shutdown")
    public ExecutorService agentResolveExecutor(GatewayProperties gatewayProperties) {
        GatewayProperties.Agent agent = gatewayProperties.getAgent();
        int configured = agent.getResolveMaxConcurrent();
        int threads = Math.max(1, Math.min(configured,
                Runtime.getRuntime().availableProcessors() * 2));
        int queueCapacity = agent.getResolveMaxQueue();
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("resolveMaxQueue must be positive");
        }
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "gateway-agent-resolve");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public AgentCapabilityResolver agentCapabilityResolver(
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            InMemoryCatalogManager catalogManager,
            CandidateRetriever candidateRetriever,
            ToolReferenceService toolReferenceService,
            TelemetryPort telemetryPort,
            @org.springframework.beans.factory.annotation.Qualifier("agentResolveExecutor")
            ExecutorService agentResolveExecutor,
            GatewayProperties gatewayProperties) {
        return new AgentCapabilityResolver(
                authenticationPort, authorizationPort, catalogManager, candidateRetriever,
                new TextNormalizer(), toolReferenceService, telemetryPort,
                agentResolveExecutor, gatewayProperties.getAgent().getResolveTimeoutMs());
    }

    @Bean
    public AgentHostToolCallUseCase agentHostToolCallUseCase(
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            InMemoryCatalogManager catalogManager,
            ToolReferenceService toolReferenceService,
            AgentToolCallUseCase agentToolCallUseCase,
            TelemetryPort telemetryPort) {
        return new AgentHostToolCallUseCase(
                authenticationPort, authorizationPort, catalogManager,
                toolReferenceService, agentToolCallUseCase, telemetryPort);
    }

    @Bean
    public PendingConfirmationStore pendingConfirmationStore(
            GatewayProperties gatewayProperties,
            TelemetryPort telemetryPort) {
        return new InMemoryPendingConfirmationStore(
                gatewayProperties.getAgent().getPendingConfirmationMaxEntries(), telemetryPort);
    }

    @Bean
    public AgentModelResultMapper agentModelResultMapper(
            PendingConfirmationStore pendingConfirmationStore) {
        return new AgentModelResultMapper(pendingConfirmationStore);
    }

    @Bean
    public AgentTurnStore agentTurnStore(GatewayProperties gatewayProperties,
                                         TelemetryPort telemetryPort) {
        return new InMemoryAgentTurnStore(
                gatewayProperties.getAgent().getTurnMaxEntries(), telemetryPort);
    }

    @Bean
    public AgentResolveAdmissionController agentResolveAdmissionController(
            GatewayProperties gatewayProperties, TelemetryPort telemetryPort) {
        return new AgentResolveAdmissionController(
                gatewayProperties.getAgent().getResolveMaxConcurrent(), telemetryPort);
    }

    @Bean
    public AgentHostConnector agentHostConnector(
            AuthenticationPort authenticationPort,
            AgentCapabilityResolver resolver,
            AgentHostToolCallUseCase callUseCase,
            AgentModelResultMapper modelResultMapper,
            AgentTurnStore turnStore,
            PendingConfirmationStore confirmationStore,
            OperationConfirmUseCase confirmUseCase,
            OperationCancelUseCase cancelUseCase,
            OperationStatusUseCase statusUseCase,
            TelemetryPort telemetryPort,
            AgentResolveAdmissionController resolveAdmission) {
        return new AgentHostConnector(authenticationPort, resolver, callUseCase,
                modelResultMapper, turnStore, confirmationStore,
                confirmUseCase, cancelUseCase, statusUseCase, telemetryPort,
                resolveAdmission);
    }

    @Bean
    public McpGatewayAdapter mcpGatewayAdapter(
            AgentHostConnector connector,
            GatewayProperties gatewayProperties,
            McpClientTrustRegistry trustRegistry,
            McpRateLimiter mcpRateLimiter,
            com.ai.gateway.adapter.mcp.McpProjectedToolCatalog mcpProjectedToolCatalog) {
        McpSecurityMode mode = McpSecurityMode.parse(
                gatewayProperties.getAgent().getMcpSecurityMode());
        assertMcpSecurityModeAllowed(mode, gatewayProperties.getEnvironment());
        if (mode == McpSecurityMode.DISABLED) {
            throw new IllegalStateException("MCP is disabled by configuration");
        }
        return new McpGatewayAdapter(connector, mode, trustRegistry, mcpRateLimiter,
                mcpProjectedToolCatalog);
    }

    /**
     * 无查询词的工具投影用例：{@code tools/list} 需要的是「我被授权的全部只读能力」，
     * 而不是「与某句自然语言最相关的 Top-K」，因此它不依赖 {@code CandidateRetriever}。
     */
    @Bean
    public AgentToolProjectionUseCase agentToolProjectionUseCase(
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            InMemoryCatalogManager catalogManager,
            ToolReferenceService toolReferenceService,
            AgentTurnStore agentTurnStore,
            AliasGenerator aliasGenerator,
            TelemetryPort telemetryPort) {
        return new AgentToolProjectionUseCase(
                authenticationPort, authorizationPort, catalogManager,
                toolReferenceService, agentTurnStore, aliasGenerator,
                CapabilityProjectionRanker.lexicographic(), telemetryPort);
    }

    /**
     * MCP 工具面的曝光策略。
     *
     * <p>曝光模式只决定「展示形态」：直投能力与 Meta-Tool 共用同一条执行边界与同一次
     * 执行时授权，因此切换模式不放宽任何安全约束，只改变模型看到的工具列表。</p>
     */
    @Bean
    public com.ai.gateway.adapter.mcp.McpProjectedToolCatalog mcpProjectedToolCatalog(
            GatewayProperties gatewayProperties,
            AgentToolProjectionUseCase agentToolProjectionUseCase,
            TelemetryPort telemetryPort) {
        GatewayProperties.Agent agent = gatewayProperties.getAgent();
        com.ai.gateway.adapter.mcp.McpToolExposureMode mode = mcpToolExposureMode(
                agent.getMcpToolExposure());
        if (!mode.projectsCapabilities()) {
            // META_TOOL 模式下不注入投影用例，杜绝「模式为纯 Meta-Tool 却仍走投影」的可能。
            return com.ai.gateway.adapter.mcp.McpProjectedToolCatalog.metaToolOnly();
        }
        return com.ai.gateway.adapter.mcp.McpProjectedToolCatalog.of(mode,
                agentToolProjectionUseCase,
                new AgentToolProjectionUseCase.ProjectionBudget(
                        agent.getMcpDirectMaxTools(), agent.getMcpDirectMaxSchemaBytes()),
                telemetryPort);
    }

    private static com.ai.gateway.adapter.mcp.McpToolExposureMode mcpToolExposureMode(
            String configured) {
        String normalized = configured == null ? "" : configured.trim();
        if (normalized.isEmpty()) {
            throw new IllegalStateException("gateway.agent.mcp-tool-exposure must not be blank");
        }
        try {
            return com.ai.gateway.adapter.mcp.McpToolExposureMode.valueOf(
                    normalized.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "gateway.agent.mcp-tool-exposure is invalid: " + configured, e);
        }
    }

    /**
     * 目录/策略纪元变化时向本节点会话推送 {@code notifications/tools/list_changed}。
     *
     * <p>推送属于体验优化而非安全机制：alias 索引每次 {@code tools/list} 都重建，
     * 授权在执行时再判一次，因此「通知没送达」最差只是客户端多拿到一次
     * {@code CAPABILITY_UNAVAILABLE}，不会放行任何越权调用。</p>
     */
    @Bean(destroyMethod = "close")
    public com.ai.gateway.adapter.mcp.McpToolListChangeBroadcaster mcpToolListChangeBroadcaster(
            McpWebMvcTransportAdapter transportAdapter,
            InMemoryCatalogManager catalogManager,
            AuthorizationPort authorizationPort,
            McpRateLimiter mcpRateLimiter,
            TelemetryPort telemetryPort,
            GatewayProperties gatewayProperties) {
        com.ai.gateway.adapter.mcp.McpToolListChangeBroadcaster broadcaster =
                new com.ai.gateway.adapter.mcp.McpToolListChangeBroadcaster(
                        () -> new com.ai.gateway.adapter.mcp.McpToolListChangeBroadcaster.Epoch(
                                catalogManager.getCurrentSnapshotVersion(),
                                authorizationPort.currentPolicyEpoch()),
                        transportAdapter.transportProvider()::notifyToolListChanged,
                        mcpRateLimiter, telemetryPort);
        broadcaster.start(java.time.Duration.ofMillis(
                gatewayProperties.getAgent().getMcpToolListWatchMs()));
        return broadcaster;
    }

    @Bean
    public McpClientTrustRegistry mcpClientTrustRegistry(
            GatewayProperties gatewayProperties) {
        List<McpClientTrustProfile> profiles = gatewayProperties.getAgent()
                .getMcpTrustedClients().stream()
                .map(BeanConfig::toMcpTrustProfile)
                .toList();
        return new McpClientTrustRegistry(profiles);
    }

    @Bean
    public McpRateLimiter mcpRateLimiter(RateLimiterManager rateLimiterManager) {
        return McpRateLimiter.from(rateLimiterManager);
    }

    @Bean(name = "mcpCallExecutorService", destroyMethod = "shutdown")
    public ExecutorService mcpCallExecutorService(GatewayProperties gatewayProperties) {
        GatewayProperties.Agent agent = gatewayProperties.getAgent();
        if (agent.getMcpCallMaxConcurrent() <= 0 || agent.getMcpCallMaxQueue() <= 0) {
            throw new IllegalArgumentException(
                    "mcpCallMaxConcurrent and mcpCallMaxQueue must be positive");
        }
        return new ThreadPoolExecutor(
                agent.getMcpCallMaxConcurrent(), agent.getMcpCallMaxConcurrent(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(agent.getMcpCallMaxQueue()),
                runnable -> {
                    Thread thread = new Thread(runnable, "gateway-mcp-call");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public com.ai.gateway.adapter.mcp.McpCallExecutor mcpCallExecutor(
            @org.springframework.beans.factory.annotation.Qualifier("mcpCallExecutorService")
            ExecutorService executor,
            TelemetryPort telemetryPort) {
        return new com.ai.gateway.adapter.mcp.BoundedMcpCallExecutor(executor, telemetryPort);
    }

    @Bean
    public McpWebMvcTransportAdapter mcpWebMvcTransportAdapter(
            ObjectMapper objectMapper,
            McpGatewayAdapter gatewayAdapter,
            AuthenticationPort authenticationPort,
            TelemetryPort telemetryPort,
            McpRateLimiter mcpRateLimiter,
            com.ai.gateway.adapter.mcp.McpCallExecutor mcpCallExecutor,
            GatewayProperties gatewayProperties) {
        GatewayProperties.Agent agent = gatewayProperties.getAgent();
        McpSecurityMode securityMode = McpSecurityMode.parse(agent.getMcpSecurityMode());
        assertMcpSecurityModeAllowed(securityMode, gatewayProperties.getEnvironment());
        com.ai.gateway.adapter.mcp.McpRequestAuthenticator requestAuthenticator =
                mcpRequestAuthenticator(securityMode, authenticationPort);
        return new McpWebMvcTransportAdapter(
                objectMapper, gatewayAdapter, requestAuthenticator, telemetryPort,
                agent.getMcpMaxSessions(),
                java.time.Duration.ofSeconds(agent.getMcpSessionIdleSeconds()),
                java.time.Duration.ofMillis(agent.getMcpCallTimeoutMs()),
                java.time.Duration.ofMillis(agent.getMcpCloseTimeoutMs()),
                agent.getMcpNodeId(), mcpRateLimiter, mcpCallExecutor);
    }

    private static com.ai.gateway.adapter.mcp.McpRequestAuthenticator mcpRequestAuthenticator(
            McpSecurityMode securityMode, AuthenticationPort authenticationPort) {
        if (securityMode != McpSecurityMode.NO_AUTH) {
            return com.ai.gateway.adapter.mcp.McpRequestAuthenticator.bearer(authenticationPort);
        }
        Principal developmentPrincipal = new Principal(
                "local-mcp-development", 0L, java.util.List.of("developer"),
                java.util.List.of("*"), Instant.now(), "LOCAL_NO_AUTH");
        return com.ai.gateway.adapter.mcp.McpRequestAuthenticator.noAuth(developmentPrincipal);
    }

    static void assertMcpSecurityModeAllowed(McpSecurityMode securityMode,
                                             String environment) {
        if (securityMode == McpSecurityMode.NO_AUTH
                && !"development".equalsIgnoreCase(environment)) {
            throw new IllegalStateException(
                    "MCP NO_AUTH is only allowed when gateway.environment=development");
        }
    }

    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(
            McpWebMvcTransportAdapter transportAdapter) {
        return transportAdapter.routerFunction();
    }

    @Bean
    public FilterRegistrationBean<McpRequestContextFilter> mcpRequestContextFilter() {
        FilterRegistrationBean<McpRequestContextFilter> registration =
                new FilterRegistrationBean<>(new McpRequestContextFilter());
        registration.addUrlPatterns("/mcp/*");
        registration.setOrder(-100);
        return registration;
    }

    private static byte[] toolReferenceKey(String configuredSecret, String environment) {
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            return configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
        if ("production".equalsIgnoreCase(environment)) {
            throw new IllegalStateException(
                    "gateway.agent.tool-ref-secret is required in production");
        }
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        return generated;
    }

    private static McpClientTrustProfile toMcpTrustProfile(
            GatewayProperties.McpTrustedClient configured) {
        if (configured == null) {
            throw new IllegalStateException("gateway.agent.mcp-trusted-clients contains null");
        }
        try {
            Instant expiresAt = configured.getExpiresAt() == null
                    || configured.getExpiresAt().isBlank()
                    ? null : Instant.parse(configured.getExpiresAt());
            return new McpClientTrustProfile(
                    configured.getClientId(),
                    configured.getTokenFingerprint(),
                    McpClientTrustProfile.TokenAssurance.valueOf(
                            configured.getTokenAssurance().trim().toUpperCase(
                                    java.util.Locale.ROOT)),
                    McpClientTrustProfile.ConfirmationChannel.valueOf(
                            configured.getConfirmationChannel().trim().toUpperCase(
                                    java.util.Locale.ROOT)),
                    configured.isEnabled(), expiresAt);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Invalid gateway.agent.mcp-trusted-clients entry", e);
        }
    }

    @Bean
    public HealthReadinessUseCase healthReadinessUseCase(
            ManifestRepository manifestRepository,
            CatalogPort catalogPort,
            com.ai.gateway.domain.port.SecretManager secretManager,
            GatewayProperties gatewayProperties) {
        return new HealthReadinessUseCase(manifestRepository, catalogPort, secretManager,
                CatalogEnvironment.DEFAULT);
    }

    /**
     * 澄清用例 — 多轮参数消歧。
     */
    @Bean
    public ClarificationUseCase clarificationUseCase(
            InteractionRepository interactionRepository,
            CandidateRetriever candidateRetriever,
            LlmRouterPort llmRouterPort,
            AliasGenerator aliasGenerator,
            ThresholdEvaluator thresholdEvaluator,
            CatalogPort catalogPort,
            GatewayProperties gatewayProperties) {
        return new ClarificationUseCase(interactionRepository, candidateRetriever, llmRouterPort,
                aliasGenerator, thresholdEvaluator, catalogPort,
                CatalogEnvironment.DEFAULT);
    }

    // ======================================================================
    // 用例 — 写操作（第 13 节）
    // ======================================================================

    /**
     * 操作准备用例 — 两阶段写之 Prepare。
     */
    @Bean
    public OperationPrepareUseCase operationPrepareUseCase(
            NaturalLanguageQueryUseCase naturalLanguageQueryUseCase,
            TypeConverterRegistry typeConverterRegistry,
            SchemaValidator schemaValidator,
            AuthorizationPort authorizationPort,
            EncryptionPort encryptionPort,
            OperationRepository operationRepository,
            CatalogPort catalogPort,
            AuthenticationPort authenticationPort,
            ConfirmationTokenCodec confirmationTokenCodec,
            ArgumentPayloadCodec argumentPayloadCodec,
            PayloadLimits payloadLimits,
            GatewayProperties gatewayProperties) {
        return new OperationPrepareUseCase(
                naturalLanguageQueryUseCase,
                typeConverterRegistry,
                schemaValidator,
                authorizationPort,
                encryptionPort,
                operationRepository,
                catalogPort,
                authenticationPort,
                confirmationTokenCodec,
                argumentPayloadCodec,
                payloadLimits,
                CatalogEnvironment.DEFAULT);
    }

    /**
     * 操作确认用例 — 两阶段写之 Confirm。
     */
    @Bean
    public OperationConfirmUseCase operationConfirmUseCase(
            OperationRepository operationRepository,
            InvocationAdapter invocationAdapter,
            AuthorizationPort authorizationPort,
            com.ai.gateway.domain.port.AuditPort auditPort,
            EncryptionPort encryptionPort,
            ConfirmationTokenCodec confirmationTokenCodec,
            ArgumentPayloadCodec argumentPayloadCodec,
            CatalogPort catalogPort,
            OperationStateMachine operationStateMachine) {
        return new OperationConfirmUseCase(
                operationRepository,
                invocationAdapter,
                authorizationPort,
                auditPort,
                encryptionPort,
                confirmationTokenCodec,
                argumentPayloadCodec,
                catalogPort,
                operationStateMachine);
    }

    /**
     * 操作取消用例 — PREPARED -> CANCELLED 的唯一归属者。
     */
    @Bean
    public OperationCancelUseCase operationCancelUseCase(
            OperationRepository operationRepository,
            com.ai.gateway.domain.port.AuditPort auditPort,
            OperationStateMachine operationStateMachine) {
        return new OperationCancelUseCase(operationRepository, auditPort, operationStateMachine);
    }

    /**
     * 操作状态用例 — 写操作状态查询。
     */
    @Bean
    public OperationStatusUseCase operationStatusUseCase(
            OperationRepository operationRepository) {
        return new OperationStatusUseCase(operationRepository);
    }

    // ======================================================================
    // 用例 — 管理控制台
    // ======================================================================

    /**
     * 控制台认证用例 — 管理控制台登录认证。
     */
    @Bean
    public ConsoleAuthUseCase consoleAuthUseCase(
            TokenIssuerPort tokenIssuerPort,
            AuditPort auditPort,
            GatewayProperties gatewayProperties) {
        return new ConsoleAuthUseCase(tokenIssuerPort, auditPort,
                gatewayProperties.getAuth().getProvider(),
                gatewayProperties.getAuth().getConsoleAdmin().getUsername(),
                gatewayProperties.getAuth().getConsoleAdmin().getPassword());
    }

    /**
     * Capability query use case — admin console capability listing.
     */
    @Bean
    public CapabilityQueryUseCase capabilityQueryUseCase(
            ManifestRepository manifestRepository,
            CatalogPort catalogPort) {
        return new CapabilityQueryUseCase(manifestRepository, catalogPort);
    }

    /**
     * Audit query use case — admin console audit event queries.
     */
    @Bean
    public AuditQueryUseCase auditQueryUseCase(AuditQueryPort auditQueryPort) {
        return new AuditQueryUseCase(auditQueryPort);
    }

    /**
     * 统计查询用例 — 管理控制台统计数据查询。
     */
    @Bean
    public StatsQueryUseCase statsQueryUseCase(StatsQueryPort statsQueryPort) {
        return new StatsQueryUseCase(statsQueryPort);
    }

    /**
     * ACL 管理用例 — 管理控制台 ACL/角色/权限管理。
     */
    @Bean
    public AclManageUseCase aclManageUseCase(AclRepository aclRepository,
                                             ManifestRepository manifestRepository) {
        return new AclManageUseCase(aclRepository, manifestRepository);
    }

    /**
     * Config query use case — admin console gateway config view.
     */
    @Bean
    public ConfigQueryUseCase configQueryUseCase(
            SentinelRuleAdminService sentinelRuleAdminService,
            InMemoryCatalogManager catalogManager,
            GatewayProperties gatewayProperties) {
        GatewayConfig config = new GatewayConfig(
                CatalogEnvironment.DEFAULT,
                gatewayProperties.getAuth().getProvider(),
                gatewayProperties.getCache().getProvider(),
                gatewayProperties.getRatelimit().getProvider(),
                gatewayProperties.getMaxRequestSizeBytes(),
                gatewayProperties.getMaxResponseBytes(),
                gatewayProperties.getDefaultTimeoutMs(),
                Map.of(),
                Map.of("batchSize", gatewayProperties.getAudit().getBatchSize(),
                        "batchWaitMillis", gatewayProperties.getAudit().getBatchWaitMillis()),
                Map.of("maxLagMillis", gatewayProperties.getSnapshot().getMaxLagMillis()),
                Map.of("baselineRules", sentinelRuleAdminService.listRules().size())
        );
        String cacheProvider = gatewayProperties.getCache().getProvider();
        CacheStatus cacheStatus = new CacheStatus(
                cacheProvider,
                "redis".equalsIgnoreCase(cacheProvider) ? "configured" : "n/a",
                gatewayProperties.getRedis().getSnapshot().getLocalTtlSeconds(),
                catalogManager.getCurrentSnapshotVersion(),
                0L);
        return new ConfigQueryUseCase(config, cacheStatus, sentinelRuleAdminService.listRules());
    }
}
