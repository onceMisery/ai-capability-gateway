package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.llm.HttpLlmRouterAdapter;
import com.ai.gateway.adapter.llm.LlmRequestBuilder;
import com.ai.gateway.adapter.llm.LlmResponseParser;
import com.ai.gateway.adapter.llm.PromptTemplateRegistry;
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
import com.ai.gateway.application.runtime.DeterministicExecutionUseCase;
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.application.runtime.StructuredInvocationUseCase;
import com.ai.gateway.application.runtime.HealthReadinessUseCase;
import com.ai.gateway.domain.model.CacheStatus;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ConverterType;
import com.ai.gateway.domain.model.GatewayConfig;
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
import com.ai.gateway.adapter.dubbo.DubboInvocationAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            com.ai.gateway.application.catalog.LuceneCandidateRetriever candidateRetriever,
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
                // Rebuild the BM25 retrieval index from the loaded snapshot
                var snapshot = catalogManager.getCurrentSnapshot();
                if (snapshot != null) {
                    candidateRetriever.rebuildIndex(snapshot);
                }
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
    public InMemoryCatalogManager inMemoryCatalogManager(CatalogPort catalogPort) {
        return new InMemoryCatalogManager(catalogPort);
    }

    /**
     * Lucene-based candidate retriever using BM25 scoring.
     */
    @Bean
    public LuceneCandidateRetriever luceneCandidateRetriever() {
        return new LuceneCandidateRetriever();
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
            TransactionPort transactionPort) {
        return new CatalogPublishUseCase(
                manifestRepository,
                catalogPort,
                snapshotNotifier,
                lifecycleStateMachine,
                transactionPort);
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
            GatewayProperties gatewayProperties) {
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
                gatewayProperties.getEnvironment());
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
            DeadlineBudgetManager deadlineBudgetManager) {
        return new DeterministicExecutionUseCase(
                invocationAdapter,
                typeConverterRegistry,
                redactionService,
                schemaValidator,
                authorizationPort,
                auditPort,
                deadlineBudgetManager);
    }

    /** Structured tool invocation shares the same deterministic execution kernel as NL. */
    @Bean
    public StructuredInvocationUseCase structuredInvocationUseCase(
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            CatalogPort catalogPort,
            SchemaValidator schemaValidator,
            TypeConverterRegistry typeConverterRegistry,
            DeterministicExecutionUseCase deterministicExecutionUseCase,
            GatewayProperties gatewayProperties) {
        return new StructuredInvocationUseCase(authenticationPort, authorizationPort,
                catalogPort, schemaValidator, typeConverterRegistry,
                deterministicExecutionUseCase, gatewayProperties.getEnvironment());
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
            ArgumentPayloadCodec argumentPayloadCodec) {
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
                argumentPayloadCodec);
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
