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
import com.ai.gateway.application.agent.InMemoryAgentTurnStore;
import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
import com.ai.gateway.application.agent.InMemoryPendingConfirmationStore;
import com.ai.gateway.application.agent.PendingConfirmationStore;
import com.ai.gateway.application.agent.ToolReferenceService;
import com.ai.gateway.adapter.mcp.McpGatewayAdapter;
import com.ai.gateway.adapter.mcp.McpRequestContextFilter;
import com.ai.gateway.adapter.mcp.McpWebMvcTransportAdapter;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.application.catalog.LuceneCandidateRetriever;
import com.ai.gateway.application.console.AclManageUseCase;
import com.ai.gateway.application.console.AuditQueryUseCase;
import com.ai.gateway.application.console.CapabilityQueryUseCase;
import com.ai.gateway.application.console.ConfigQueryUseCase;
import com.ai.gateway.application.console.ConsoleAuthUseCase;
import com.ai.gateway.application.console.StatsQueryUseCase;
import com.ai.gateway.application.controlplane.CapabilitySuspendUseCase;
import com.ai.gateway.application.controlplane.CatalogPublishUseCase;
import com.ai.gateway.application.controlplane.CatalogRollbackUseCase;
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
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.application.runtime.StructuredInvocationUseCase;
import com.ai.gateway.application.runtime.HealthReadinessUseCase;
import com.ai.gateway.domain.model.CacheStatus;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manual bean assembly for the AI Capability Gateway.
 *
 * <p>This is the <b>single place</b> where adapter implementations are wired
 * to application use cases. Adapter beans are injected into use case
 * constructors by Spring's dependency resolution — no field injection or
 * {@code @Autowired} is used.</p>
 *
 * <p>Port interfaces that have dedicated adapter implementations (e.g.,
 * {@link CatalogPort}, {@link ManifestRepository}) are brought in via
 * {@code @Import} on the main application class and resolved here as
 * constructor parameters.</p>
 *
 * <p>Port interfaces without dedicated adapters (e.g.,
 * {@link AuthenticationPort}, {@link EncryptionPort}) receive inline stub
 * implementations in this class. These stubs follow the spec's initial-release
 * degradation rules: authorization is optional, encryption uses
 * Base64 (development only), etc.</p>
 *
 * <p><b>ArgumentBinder</b> and <b>ResultNormalizer</b> are per-request domain
 * services that require runtime context (Principal, SystemContext,
 * CapabilityManifest, OutputContract). They are created by the use cases
 * on each request and do not have singleton {@code @Bean} definitions.</p>
 *
 * @since 0.1.0
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
    // Domain Services
    // ======================================================================

    /**
     * Alias generator for stable parameter-name resolution.
     */
    @Bean
    public AliasGenerator aliasGenerator() {
        return new AliasGenerator();
    }

    /**
     * Redaction service for sensitive-data masking.
     */
    @Bean
    public RedactionService redactionService() {
        return new RedactionService();
    }

    /**
     * Manifest validator performing the 10-step validation pipeline
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
                gatewayProperties.getEnvironment());
    }

    /**
     * Lifecycle state machine for manifest transitions.
     */
    @Bean
    public LifecycleStateMachine lifecycleStateMachine() {
        return new LifecycleStateMachine();
    }

    /**
     * Operation state machine for two-phase write operations.
     */
    @Bean
    public OperationStateMachine operationStateMachine() {
        return new OperationStateMachine();
    }

    /**
     * Text normalizer for query pre-processing.
     */
    @Bean
    public TextNormalizer textNormalizer() {
        return new TextNormalizer();
    }

    /**
     * Threshold evaluator for LLM decision confidence checks.
     */
    @Bean
    public ThresholdEvaluator thresholdEvaluator() {
        return new ThresholdEvaluator();
    }

    /**
     * Deadline budget manager for end-to-end timeout enforcement
     */
    @Bean
    public DeadlineBudgetManager deadlineBudgetManager() {
        return new DeadlineBudgetManager();
    }

    /**
     * Loads the latest catalog snapshot on application startup so that
     * the in-memory catalog is immediately available for routing.
     */
    @Bean
    public org.springframework.boot.ApplicationRunner catalogStartupLoader(
            InMemoryCatalogManager catalogManager,
            com.ai.gateway.adapter.dubbo.DubboReferenceManager dubboReferenceManager,
            GatewayProperties gatewayProperties,
            @org.springframework.beans.factory.annotation.Value("${dubbo.registry.address:nacos://nacos.dev.com:8848}") String dubboRegistryAddress) {
        return args -> {
            // Register the Dubbo registry address for manifest registryRef resolution
            dubboReferenceManager.registerRegistryAddress("nacos-main", dubboRegistryAddress);

            log.info("Loading catalog snapshot on startup...");
            boolean loaded = catalogManager.loadAndActivate(gatewayProperties.getEnvironment());
            if (loaded) {
                log.info("Catalog snapshot loaded successfully on startup: version={}",
                        catalogManager.getCurrentSnapshotVersion());
            } else {
                log.warn("No catalog snapshot available on startup (first run or no publish yet)");
            }
        };
    }

    // ======================================================================
    // Stub Port Implementations (Ports without dedicated adapters)
    // ======================================================================
    //
    // AuthenticationPort/AuthorizationPort stubs live in StubAuthConfiguration
    // (conditional on gateway.auth.provider). EncryptionPort/CompatibilityTestPort
    // stubs live in StubAdaptersConfiguration with a production fail-fast guard.

    /**
     * {@link TypeConverterRegistry} implementing the three built-in
     * controlled type converters.
     *
     * <p>The closed whitelist contains:</p>
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
    // LLM HTTP Adapter
    // ======================================================================

    /**
     * Creates the {@link HttpLlmRouterAdapter} as a {@link LlmRouterPort}
     * bean with configuration values from {@code application.yml}.
     *
     * <p>The adapter cannot be {@code @Import}-ed because its constructor
     * requires non-bean parameters (endpoint URL, API key, model name,
     * temperature, max tokens) that are resolved from configuration
     * properties, not from Spring autowiring.</p>
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

    /** Primary runtime adapter; the concrete Dubbo bean remains available for diagnostics. */
    @Bean
    @Primary
    public InvocationAdapter resilientInvocationAdapter(
            DubboInvocationAdapter delegate,
            RateLimiterManager rateLimiterManager,
            CircuitBreakerManager circuitBreakerManager,
            BulkheadManager bulkheadManager,
            TelemetryPort telemetryPort) {
        return new ResilientInvocationAdapter(delegate, rateLimiterManager,
                circuitBreakerManager, bulkheadManager, telemetryPort);
    }

    // ======================================================================
    // Catalog Managers
    // ======================================================================

    /**
     * In-memory catalog manager that caches the active snapshot and
     * coordinates index rebuilding.
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
     * Lucene-based candidate retriever using BM25 scoring.
     */
    @Bean
    public LuceneCandidateRetriever luceneCandidateRetriever() {
        return new LuceneCandidateRetriever();
    }

    /**
     * Isolates database snapshot reads and Lucene/View construction from
     * request and Redis listener threads. A single worker also prevents
     * overlapping generations from doubling the catalog build footprint.
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
    // Resilience Managers (Section 18)
    // ======================================================================

    /**
     * Rate limiter manager for bounded resource enforcement.
     */
    @Bean
    public RateLimiterManager rateLimiterManager(RateLimiterPort rateLimiterPort) {
        return new RateLimiterManager(rateLimiterPort);
    }

    /**
     * Circuit breaker manager for Provider/Capability fault isolation
     */
    @Bean
    public CircuitBreakerManager circuitBreakerManager() {
        return new CircuitBreakerManager();
    }

    /**
     * Bulkhead manager for per-Provider/Capability concurrency isolation
     */
    @Bean
    public BulkheadManager bulkheadManager() {
        return new BulkheadManager();
    }

    /**
     * Fault handler for determining fault responses.
     */
    @Bean
    public FaultHandler faultHandler() {
        return new FaultHandler();
    }

    // ======================================================================
    // Use Cases — Control Plane (Section 8)
    // ======================================================================

    /**
     * Manifest import use case — 10-step validation pipeline.
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
    public CatalogSnapshotQueryUseCase catalogSnapshotQueryUseCase(CatalogPort catalogPort) {
        return new CatalogSnapshotQueryUseCase(catalogPort);
    }

    /**
     * Manifest approval use case — lifecycle state transition
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
     * Catalog publish use case — single-transaction publication
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
     * Catalog rollback use case — historical snapshot copy.
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
     * Capability suspend use case — emergency suspension.
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
                gatewayProperties.getEnvironment(), lifecycleStateMachine, transactionPort);
    }

    // ======================================================================
    // Use Cases — Runtime Plane (Section 9)
    // ======================================================================

    /**
     * Natural-language query use case — 11-step routing pipeline
     */
    @Bean
    public NaturalLanguageQueryUseCase naturalLanguageQueryUseCase(
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            CatalogPort catalogPort,
            CandidateRetriever candidateRetriever,
            LlmRouterPort llmRouterPort,
            SchemaValidator schemaValidator,
            AliasGenerator aliasGenerator,
            TypeConverterRegistry typeConverterRegistry,
            RedactionService redactionService,
            com.ai.gateway.domain.port.AuditPort auditPort,
            ThresholdEvaluator thresholdEvaluator,
            DeadlineBudgetManager deadlineBudgetManager,
            InteractionRepository interactionRepository,
            DeterministicExecutionUseCase deterministicExecutionUseCase,
            GatewayProperties gatewayProperties,
            PayloadLimits payloadLimits) {
        return new NaturalLanguageQueryUseCase(
                authenticationPort,
                authorizationPort,
                catalogPort,
                candidateRetriever,
                llmRouterPort,
                schemaValidator,
                aliasGenerator,
                typeConverterRegistry,
                redactionService,
                auditPort,
                thresholdEvaluator,
                deadlineBudgetManager,
                new TextNormalizer(),
                interactionRepository,
                deterministicExecutionUseCase,
                gatewayProperties.getEnvironment(),
                payloadLimits);
    }

    /**
     * Deterministic execution use case — Provider invocation with
     * result normalization (Section 11).
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

    /** Structured tool invocation shares the same deterministic execution kernel as NL. */
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
                deterministicExecutionUseCase, gatewayProperties.getEnvironment(),
                payloadLimits);
    }

    /** Agent-facing discovery keeps only a small authorized Top-K in context. */
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
                gatewayProperties.getEnvironment());
    }

    /** Unified Agent read/prepare dispatcher. */
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
                gatewayProperties.getEnvironment());
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
        int configured = gatewayProperties.getAgent().getResolveMaxConcurrent();
        int threads = Math.max(1, Math.min(configured,
                Runtime.getRuntime().availableProcessors() * 2));
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "gateway-agent-resolve");
            thread.setDaemon(true);
            return thread;
        });
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
            AgentHostConnector connector) {
        return new McpGatewayAdapter(connector);
    }

    @Bean
    public McpWebMvcTransportAdapter mcpWebMvcTransportAdapter(
            ObjectMapper objectMapper,
            McpGatewayAdapter gatewayAdapter,
            AuthenticationPort authenticationPort,
            TelemetryPort telemetryPort,
            GatewayProperties gatewayProperties) {
        GatewayProperties.Agent agent = gatewayProperties.getAgent();
        return new McpWebMvcTransportAdapter(
                objectMapper, gatewayAdapter, authenticationPort, telemetryPort,
                agent.getMcpMaxSessions(),
                java.time.Duration.ofSeconds(agent.getMcpSessionIdleSeconds()));
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

    @Bean
    public HealthReadinessUseCase healthReadinessUseCase(
            ManifestRepository manifestRepository,
            CatalogPort catalogPort,
            com.ai.gateway.domain.port.SecretManager secretManager,
            GatewayProperties gatewayProperties) {
        return new HealthReadinessUseCase(manifestRepository, catalogPort, secretManager,
                gatewayProperties.getEnvironment());
    }

    /**
     * Clarification use case — multi-turn parameter disambiguation
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
                gatewayProperties.getEnvironment());
    }

    // ======================================================================
    // Use Cases — Write Operation (Section 13)
    // ======================================================================

    /**
     * Operation prepare use case — two-phase write Prepare.
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
                gatewayProperties.getEnvironment());
    }

    /**
     * Operation confirm use case — two-phase write Confirm.
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
     * Operation cancel use case — the sole owner of PREPARED -> CANCELLED.
     */
    @Bean
    public OperationCancelUseCase operationCancelUseCase(
            OperationRepository operationRepository,
            com.ai.gateway.domain.port.AuditPort auditPort,
            OperationStateMachine operationStateMachine) {
        return new OperationCancelUseCase(operationRepository, auditPort, operationStateMachine);
    }

    /**
     * Operation status use case — write operation status query
     */
    @Bean
    public OperationStatusUseCase operationStatusUseCase(
            OperationRepository operationRepository) {
        return new OperationStatusUseCase(operationRepository);
    }

    // ======================================================================
    // Use Cases — Admin Console
    // ======================================================================

    /**
     * Console auth use case — admin console authentication.
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
     * Stats query use case — admin console statistics queries.
     */
    @Bean
    public StatsQueryUseCase statsQueryUseCase(StatsQueryPort statsQueryPort) {
        return new StatsQueryUseCase(statsQueryPort);
    }

    /**
     * ACL manage use case — admin console ACL/role/permission management.
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
                gatewayProperties.getEnvironment(),
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
