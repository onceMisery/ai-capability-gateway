package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ExecutionPlan;
import com.ai.gateway.domain.model.ModelDecision;
import com.ai.gateway.domain.model.NlInteraction;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.RoutingThresholds;
import com.ai.gateway.domain.model.SystemContext;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.InteractionRepository;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import com.ai.gateway.domain.service.AliasGenerator;
import com.ai.gateway.domain.service.ArgumentBinder;
import com.ai.gateway.domain.service.DeadlineBudgetManager;
import com.ai.gateway.domain.service.RedactionService;
import com.ai.gateway.domain.service.ResultNormalizer;
import com.ai.gateway.domain.service.Sha256Digest;
import com.ai.gateway.domain.service.ManifestDigest;
import com.ai.gateway.domain.service.TextNormalizer;
import com.ai.gateway.domain.service.ThresholdEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.StringJoiner;

/**
 * Use case implementing the complete natural-language routing pipeline
 * defined in of the design document (11 steps).
 *
 * <p>The pipeline executes in strict order:</p>
 * <ol>
 * <li>Authenticate and construct Principal.</li>
 * <li>Fix catalog snapshot.</li>
 * <li>Permission and environment pre-filter — first phase: authentication
 * check only.</li>
 * <li>Text normalization (TextNormalizer — ).</li>
 * <li>BM25 Top-K retrieval.</li>
 * <li>Threshold/discrimination screening (ThresholdEvaluator — ).</li>
 * <li>Construct request-scoped short aliases (AliasGenerator — ).</li>
 * <li>LLM selects capability and extracts MODEL parameters.</li>
 * <li>Selection legality and Schema validation.</li>
 * <li>Re-authorize before execution.</li>
 * <li>Invoke or initiate clarification.</li>
 * </ol>
 *
 * <p>The use case returns a {@link QueryResult} with one of the following
 * statuses:</p>
 * <ul>
 * <li>{@code COMPLETED} — a capability was selected and an execution plan
 * generated. For read-only operations, the plan may be executed
 * immediately by {@link DeterministicExecutionUseCase}.</li>
 * <li>{@code CLARIFICATION_REQUIRED} — the model or threshold evaluator
 * determined that clarification is needed; an interactionId is returned
 * for the clarification session.</li>
 * <li>{@code NO_MATCH} — no capability matched the request.</li>
 * <li>{@code ERROR} — an error occurred during routing.</li>
 * </ul>
 *
 * <p>Note: {@link ArgumentBinder} and {@link ResultNormalizer} are per-request
 * domain services that require request-scoped context (Principal,
 * SystemContext, CapabilityManifest, OutputContract). This use case receives
 * the shared dependencies ({@link TypeConverterRegistry} and
 * {@link RedactionService}) via constructor injection and constructs
 * per-request instances as needed.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable per-request state.</p>
 *
 * @see AuthenticationPort
 * @see AuthorizationPort
 * @see CandidateRetriever
 * @see LlmRouterPort
 * @see ThresholdEvaluator
 * @see AliasGenerator
 * @since 0.1.0
 */
public final class NaturalLanguageQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageQueryUseCase.class);

    /**
     * Default routing thresholds for the initial release.
     */
    private static final RoutingThresholds DEFAULT_THRESHOLDS = new RoutingThresholds(
            1.0, // minRelevanceScore
            0.5, // minTop1Top2ScoreDiff
            5, // maxCandidates (Top-K)
            4096 // maxTokenBudget
    );

    /**
     * Default clarification session TTL in seconds (5 minutes).
     */
    private static final long CLARIFICATION_TTL_SECONDS = 300;

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final CatalogPort catalogPort;
    private final CandidateRetriever candidateRetriever;
    private final LlmRouterPort llmRouterPort;
    private final SchemaValidator schemaValidator;
    private final AliasGenerator aliasGenerator;
    private final TypeConverterRegistry typeConverterRegistry;
    private final RedactionService redactionService;
    private final AuditPort auditPort;
    private final ThresholdEvaluator thresholdEvaluator;
    private final DeadlineBudgetManager deadlineBudgetManager;
    private final TextNormalizer textNormalizer;
    private final InteractionRepository interactionRepository;
    private final DeterministicExecutionUseCase deterministicExecutionUseCase;
    private final String environment;

    /**
     * Constructs a new NaturalLanguageQueryUseCase with the required
     * dependencies.
     *
     * <p>Note: {@code typeConverterRegistry} is the shared dependency for
     * constructing per-request {@link ArgumentBinder} instances, and
     * {@code redactionService} is the shared dependency for constructing
     * per-request {@link ResultNormalizer} instances.</p>
     *
     * @param authenticationPort the authentication port
     * @param authorizationPort the authorization port
     * @param catalogPort the catalog port for snapshot loading
     * @param candidateRetriever the BM25 candidate retriever
     * @param llmRouterPort the LLM routing port
     * @param schemaValidator the JSON Schema validator
     * @param aliasGenerator the short alias generator
     * @param typeConverterRegistry the type converter registry for ArgumentBinder
     * @param redactionService the redaction service for ResultNormalizer
     * @param auditPort the audit port
     * @param thresholdEvaluator the threshold evaluator
     * @param deadlineBudgetManager the deadline budget manager
     * @param textNormalizer the text normalizer for query preprocessing
     * @param interactionRepository the interaction repository for clarification sessions
     * @param deterministicExecutionUseCase the deterministic execution use case for Provider invocation
     * @param environment the target environment name (e.g., "production")
     * @throws NullPointerException if any argument is null
     */
    public NaturalLanguageQueryUseCase(AuthenticationPort authenticationPort,
                                        AuthorizationPort authorizationPort,
                                        CatalogPort catalogPort,
                                        CandidateRetriever candidateRetriever,
                                        LlmRouterPort llmRouterPort,
                                        SchemaValidator schemaValidator,
                                        AliasGenerator aliasGenerator,
                                        TypeConverterRegistry typeConverterRegistry,
                                        RedactionService redactionService,
                                        AuditPort auditPort,
                                        ThresholdEvaluator thresholdEvaluator,
                                        DeadlineBudgetManager deadlineBudgetManager,
                                        TextNormalizer textNormalizer,
                                        InteractionRepository interactionRepository,
                                        DeterministicExecutionUseCase deterministicExecutionUseCase,
                                        String environment) {
        this.authenticationPort = java.util.Objects.requireNonNull(authenticationPort,
                "authenticationPort must not be null");
        this.authorizationPort = java.util.Objects.requireNonNull(authorizationPort,
                "authorizationPort must not be null");
        this.catalogPort = java.util.Objects.requireNonNull(catalogPort,
                "catalogPort must not be null");
        this.candidateRetriever = java.util.Objects.requireNonNull(candidateRetriever,
                "candidateRetriever must not be null");
        this.llmRouterPort = java.util.Objects.requireNonNull(llmRouterPort,
                "llmRouterPort must not be null");
        this.schemaValidator = java.util.Objects.requireNonNull(schemaValidator,
                "schemaValidator must not be null");
        this.aliasGenerator = java.util.Objects.requireNonNull(aliasGenerator,
                "aliasGenerator must not be null");
        this.typeConverterRegistry = java.util.Objects.requireNonNull(typeConverterRegistry,
                "typeConverterRegistry must not be null");
        this.redactionService = java.util.Objects.requireNonNull(redactionService,
                "redactionService must not be null");
        this.auditPort = java.util.Objects.requireNonNull(auditPort,
                "auditPort must not be null");
        this.thresholdEvaluator = java.util.Objects.requireNonNull(thresholdEvaluator,
                "thresholdEvaluator must not be null");
        this.deadlineBudgetManager = java.util.Objects.requireNonNull(deadlineBudgetManager,
                "deadlineBudgetManager must not be null");
        this.interactionRepository = java.util.Objects.requireNonNull(interactionRepository,
                "interactionRepository must not be null");
        this.textNormalizer = java.util.Objects.requireNonNull(textNormalizer,
                "textNormalizer must not be null");
        this.deterministicExecutionUseCase = java.util.Objects.requireNonNull(deterministicExecutionUseCase,
                "deterministicExecutionUseCase must not be null");
        this.environment = java.util.Objects.requireNonNull(environment,
                "environment must not be null");
    }

    /**
     * Executes the complete 11-step natural-language routing pipeline
     *
     * @param requestContext the caller's request context carrying the
     * authentication credential (headers, cookies, query parameters)
     * @param text the natural-language request text
     * @param locale the request locale (e.g., "zh-CN")
     * @param timezone the request timezone
     * @return the query result
     * @throws NullPointerException if any argument is null
     */
    public QueryResult execute(RequestContext requestContext, String text, String locale, String timezone) {
        return execute(requestContext, UUID.randomUUID().toString(), text, locale, timezone);
    }

    /** Executes the routing pipeline using the caller-provided correlation ID. */
    public QueryResult execute(RequestContext requestContext, String requestId,
                               String text, String locale, String timezone) {
        java.util.Objects.requireNonNull(requestContext, "requestContext must not be null");
        java.util.Objects.requireNonNull(requestId, "requestId must not be null");
        java.util.Objects.requireNonNull(text, "text must not be null");
        java.util.Objects.requireNonNull(locale, "locale must not be null");
        java.util.Objects.requireNonNull(timezone, "timezone must not be null");
        long startTime = System.currentTimeMillis();
        log.info("NL routing started: requestId={}, locale={}", requestId, locale);

        // Step 1: Authenticate and construct Principal
        Principal principal;
        try {
            principal = authenticationPort.authenticate(requestContext);
        } catch (Exception e) {
            log.warn("Authentication failed: requestId={}, error={}", requestId, e.getMessage());
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null, 0,
                    ErrorCode.AUTHENTICATION_FAILED.name(), "Authentication failed"),
                    null, requestId, 0L, null, startTime);
        }

        // Record REQUEST_ACCEPTED audit event
        try {
            auditPort.recordAccepted(requestId, computePrincipalDigest(principal), principal.orgId());
        } catch (Exception e) {
            log.error("Failed to persist REQUEST_ACCEPTED audit event: {}", e.getMessage());
            return new QueryResult(QueryStatus.ERROR, null, null, null, 0,
                    ErrorCode.AUTHENTICATION_FAILED.name(),
                    "Audit persistence failed — Fail Closed");
        }

        // Step 2: Fix catalog snapshot
        CatalogSnapshot snapshot;
        try {
            snapshot = catalogPort.loadCurrentSnapshot(environment);
        } catch (Exception e) {
            log.error("Failed to load catalog snapshot: {}", e.getMessage());
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null, 0,
                    ErrorCode.PROTOCOL_ERROR.name(), "Failed to load catalog snapshot"),
                    principal, requestId, 0L, null, startTime);
        }
        if (snapshot == null || snapshot.capabilities().isEmpty()) {
            return auditRoutingTerminal(new QueryResult(QueryStatus.NO_MATCH, null, null, null, 0,
                    ErrorCode.NO_CAPABILITY_MATCH.name(), "No capabilities published"),
                    principal, requestId, 0L, null, startTime);
        }
        long snapshotVersion = snapshot.snapshotVersion();

        // Step 3: Permission and environment pre-filter
        // Visibility authorization: filter to only capabilities the Principal can see
        List<CapabilityManifest> visibleCapabilities;
        try {
            visibleCapabilities = authorizationPort.filterVisibleCapabilities(
                    principal, snapshot.capabilities());
        } catch (Exception e) {
            log.error("Visibility authorization failed: {}", e.getMessage());
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null, snapshotVersion,
                    ErrorCode.PERMISSION_DENIED.name(),
                    "Authorization data source unavailable"),
                    principal, requestId, snapshotVersion, null, startTime);
        }
        if (visibleCapabilities.isEmpty()) {
            return auditRoutingTerminal(new QueryResult(QueryStatus.NO_MATCH, null, null, null, snapshotVersion,
                    ErrorCode.NO_CAPABILITY_MATCH.name(),
                    "No visible capabilities for this principal"),
                    principal, requestId, snapshotVersion, null, startTime);
        }

        // Step 4: Text normalization
        String normalizedText = textNormalizer.normalize(text);
        if (normalizedText.isBlank()) {
            return auditRoutingTerminal(new QueryResult(QueryStatus.NO_MATCH, null, null, null, snapshotVersion,
                    ErrorCode.NO_CAPABILITY_MATCH.name(),
                    "Normalized text is empty after stop-word removal"),
                    principal, requestId, snapshotVersion, null, startTime);
        }

        // Step 5: BM25 Top-K retrieval
        List<CandidateRetriever.ScoredCapability> candidates;
        try {
            candidates = candidateRetriever.retrieve(
                    normalizedText, visibleCapabilities, DEFAULT_THRESHOLDS.maxCandidates());
        } catch (Exception e) {
            log.error("Candidate retrieval failed: {}", e.getMessage());
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null, snapshotVersion,
                    ErrorCode.PROTOCOL_ERROR.name(),
                    "Candidate retrieval failed"),
                    principal, requestId, snapshotVersion, null, startTime);
        }

        // Step 6: Threshold/discrimination screening
        ThresholdEvaluator.ThresholdResult thresholdResult =
                thresholdEvaluator.evaluate(candidates, DEFAULT_THRESHOLDS);

        switch (thresholdResult.decision()) {
            case NO_MATCH -> {
                log.info("No match after threshold evaluation: requestId={}", requestId);
                return auditRoutingTerminal(new QueryResult(QueryStatus.NO_MATCH, null, null, null,
                        snapshotVersion, ErrorCode.NO_CAPABILITY_MATCH.name(),
                        thresholdResult.noMatchReason()),
                        principal, requestId, snapshotVersion, null, startTime);
            }
            case CLARIFY -> {
                // Step 11 (clarification path): initiate clarification session
                return initiateClarification(
                        thresholdResult.clarificationCandidates(),
                        thresholdResult.clarificationQuestion(),
                        principal, snapshotVersion, requestId, startTime);
            }
            case SELECT -> {
                // Continue to step 7
                return processSelectDecision(
                        thresholdResult.selectedCandidate().orElseThrow(),
                        principal, snapshotVersion, requestId, text, locale, startTime);
            }
            default -> {
                return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null,
                        snapshotVersion, ErrorCode.PROTOCOL_ERROR.name(),
                        "Unknown threshold decision"),
                        principal, requestId, snapshotVersion, null, startTime);
            }
        }
    }

    /**
     * Processes a SELECT decision from the threshold evaluator through
     * steps 7-11.
     *
     * @param selected the selected scored capability
     * @param principal the authenticated principal
     * @param snapshotVersion the fixed snapshot version
     * @param requestId the request identifier
     * @param originalText the original user text
     * @param locale the request locale
     * @param startTime the pipeline start time
     * @return the query result
     */
    private QueryResult processSelectDecision(CandidateRetriever.ScoredCapability selected,
                                               Principal principal,
                                               long snapshotVersion,
                                               String requestId,
                                               String originalText,
                                               String locale,
                                               long startTime) {
        CapabilityManifest manifest = selected.capability();

        // Step 7: Construct request-scoped short aliases
        String alias = aliasGenerator.generate(
                snapshotVersion, manifest.metadata().id(), manifest.metadata().version());

        // Build the LLM candidate
        LlmRouterPort.LlmCandidate llmCandidate = new LlmRouterPort.LlmCandidate(
                alias,
                manifest.spec().displayName(),
                manifest.spec().description(),
                manifest.spec().examples().positive(),
                manifest.spec().examples().negative(),
                manifest.spec().examples().synonyms(),
                manifest.spec().inputSchema()
        );

        // Step 8: LLM selects capability and extracts MODEL parameters
        ModelDecision decision;
        try {
            decision = llmRouterPort.route(originalText, List.of(llmCandidate));
        } catch (LlmRouterPort.LlmRoutingException e) {
            log.warn("LLM routing unavailable: code={}", e.errorCode());
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null,
                    snapshotVersion, e.errorCode().name(), safeLlmErrorMessage(e.errorCode())),
                    principal, requestId, snapshotVersion, manifest, startTime);
        } catch (Exception e) {
            log.error("LLM routing failed");
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null,
                    snapshotVersion, ErrorCode.PROTOCOL_ERROR.name(),
                    "LLM routing failed"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        // Step 9: Selection legality and Schema validation
        if (decision instanceof ModelDecision.NoMatchDecision noMatch) {
            return auditRoutingTerminal(new QueryResult(QueryStatus.NO_MATCH, null, null, null,
                    snapshotVersion, ErrorCode.NO_CAPABILITY_MATCH.name(),
                    "LLM returned NO_MATCH: " + noMatch.reasonCode()),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        if (decision instanceof ModelDecision.ClarifyDecision clarify) {
            // Initiate clarification session with the single candidate
            return initiateClarification(
                    List.of(selected), clarify.question(),
                    principal, snapshotVersion, requestId, startTime);
        }

        if (!(decision instanceof ModelDecision.SelectDecision select)) {
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null,
                    snapshotVersion, ErrorCode.INVALID_MODEL_OUTPUT.name(),
                    "Unknown model decision type"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        // Verify the selected alias belongs to this request's candidate set
        if (!select.alias().equals(alias)) {
            log.warn("LLM selected alias {} not in candidate set (expected {})",
                    select.alias(), alias);
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null,
                    snapshotVersion, ErrorCode.INVALID_MODEL_OUTPUT.name(),
                    "Selected alias not in candidate set"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        // Validate model arguments against the input Schema
        com.ai.gateway.domain.model.ValidationReport schemaReport;
        try {
            schemaReport = schemaValidator.validate(
                    select.arguments(), manifest.spec().inputSchema());
        } catch (Exception e) {
            log.error("Model output schema validation failed: capability={}",
                    manifest.metadata().id());
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null,
                    snapshotVersion, ErrorCode.INVALID_MODEL_OUTPUT.name(),
                    "Model output validation unavailable"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }
        if (!schemaReport.valid()) {
            log.warn("Model output failed schema validation: {}", schemaReport.errors());
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null,
                    snapshotVersion, ErrorCode.INVALID_MODEL_OUTPUT.name(),
                    "Model output failed schema validation"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        // Step 10: Re-authorize before execution
        boolean authorized;
        try {
            authorized = authorizationPort.authorizeExecution(
                    principal, manifest.metadata().id(), manifest.metadata().version());
        } catch (Exception e) {
            log.error("Execution authorization data source failed: capability={}",
                    manifest.metadata().id());
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null,
                    snapshotVersion, ErrorCode.PERMISSION_DENIED.name(),
                    "Authorization data source unavailable"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }
        if (!authorized) {
            log.warn("Execution authorization denied for capability {}",
                    manifest.metadata().id());
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null,
                    snapshotVersion, ErrorCode.PERMISSION_DENIED.name(),
                    "Execution authorization denied"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        // Step 11: Invoke or initiate clarification
        // For read-only operations, execute immediately via DeterministicExecutionUseCase.
        // For write operations, return the execution plan for the two-phase
        // Prepare/Confirm protocol.
        long durationMs = System.currentTimeMillis() - startTime;

        // Construct the execution plan
        String executionId = UUID.randomUUID().toString();
        String principalDigest = computePrincipalDigest(principal);
        // Compute manifest digest from id + version (content digest not stored in Metadata)
        String manifestDigest = ManifestDigest.sha256(manifest);

        // Build resolved protocol arguments using ArgumentBinder (resolves PRINCIPAL, MODEL, CONSTANT)
        SystemContext bindContext = new SystemContext(
                requestId, System.currentTimeMillis() + 15000, null, "zh-CN");
        List<Object> resolvedArgs;
        try {
            ArgumentBinder argumentBinder = new ArgumentBinder(
                    typeConverterRegistry, schemaValidator, principal, bindContext, manifest);
            resolvedArgs = argumentBinder.bind(select.arguments());
        } catch (Exception e) {
            log.warn("Resolved argument validation failed: capability={}",
                    manifest.metadata().id());
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR, null, null, null,
                    snapshotVersion, ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                    "Resolved arguments failed validation"),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        // Construct resilience policy from manifest spec
        ResiliencePolicy resiliencePolicy = manifest.spec().resilience() != null
                ? manifest.spec().resilience()
                : new ResiliencePolicy(15000, 0, 10, true);

        ExecutionPlan plan = new ExecutionPlan(
                executionId,
                principalDigest,
                snapshotVersion,
                manifest.metadata().id(),
                manifest.metadata().version(),
                manifestDigest,
                select.arguments(),
                resolvedArgs,
                "policy-" + executionId,
                manifest.spec().risk(),
                resiliencePolicy
        );

        // For WRITE operations, return the plan for two-phase protocol
        if (manifest.spec().risk() == RiskLevel.WRITE_LOW
                || manifest.spec().risk() == RiskLevel.WRITE_HIGH) {
            Map<String, Object> planData = new HashMap<>();
            planData.put("executionId", executionId);
            planData.put("capabilityId", manifest.metadata().id());
            planData.put("capabilityVersion", manifest.metadata().version());
            planData.put("risk", manifest.spec().risk().name());
            planData.put("requiresConfirmation", true);
            planData.put("modelArguments", Map.copyOf(select.arguments()));

            log.info("NL routing completed (write): requestId={}, capability={}, durationMs={}",
                    requestId, manifest.metadata().id(), durationMs);

            return auditRoutingTerminal(new QueryResult(QueryStatus.COMPLETED, planData,
                    "Write operation requires confirmation: " + manifest.spec().displayName(),
                    null, snapshotVersion, null, null,
                    capabilityMetadata(manifest),
                    Map.of("id", executionId, "status", "PLAN_READY",
                            "requiresConfirmation", true), null),
                    principal, requestId, snapshotVersion, manifest, startTime);
        }

        // For READ_ONLY operations, execute immediately
        DeterministicExecutionUseCase.ExecutionResult execResult =
                deterministicExecutionUseCase.execute(requestId, plan, principal, manifest);

        long totalDurationMs = System.currentTimeMillis() - startTime;

        if (execResult.errorCode() != null) {
            log.warn("NL execution failed: requestId={}, capability={}, error={}",
                    requestId, manifest.metadata().id(), execResult.errorCode());
            return new QueryResult(QueryStatus.ERROR, null, null, null,
                    snapshotVersion, execResult.errorCode(), execResult.summary());
        }

        log.info("NL routing+execution completed: requestId={}, capability={}, durationMs={}",
                requestId, manifest.metadata().id(), totalDurationMs);

        return new QueryResult(QueryStatus.COMPLETED, execResult.data(),
                execResult.summary(), null, snapshotVersion, null, null,
                capabilityMetadata(manifest),
                Map.of("id", executionId, "status", "COMPLETED"), null);
    }

    /**
     * Builds a safe audit detail JSON string.
     *
     * @param alias the selected alias
     * @param errorCode the error code, or null on success
     * @return the JSON detail string
     */
    private String buildAuditDetail(String alias, String errorCode) {
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        joiner.add("\"alias\":\"" + escapeJson(alias) + "\"");
        if (errorCode != null) {
            joiner.add("\"errorCode\":\"" + escapeJson(errorCode) + "\"");
        }
        return joiner.toString();
    }

    /**
     * Escapes special characters for safe JSON string embedding.
     *
     * @param value the raw value
     * @return the escaped value
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Initiates a clarification session.
     *
     * <p>Creates a short-lived interaction record and returns a
     * CLARIFICATION_REQUIRED result with the interactionId.</p>
     *
     * @param candidates the ambiguous candidate capabilities
     * @param question the clarification question
     * @param principal the authenticated principal
     * @param snapshotVersion the fixed snapshot version
     * @param requestId the request identifier
     * @return the clarification result
     */
    private QueryResult initiateClarification(List<CandidateRetriever.ScoredCapability> candidates,
                                               String question,
                                               Principal principal,
                                               long snapshotVersion,
                                               String requestId,
                                               long startTime) {
        String interactionId = UUID.randomUUID().toString();
        String principalDigest = computePrincipalDigest(principal);

        List<String> candidateIds = candidates.stream()
                .map(c -> c.capability().metadata().id())
                .toList();

        Instant expiresAt = Instant.now().plusSeconds(CLARIFICATION_TTL_SECONDS);
        NlInteraction interaction = new NlInteraction(
                interactionId,
                principalDigest,
                snapshotVersion,
                candidateIds,
                Map.of(),
                List.of(),
                expiresAt
        );

        try {
            interactionRepository.save(interaction);
        } catch (Exception e) {
            log.error("Clarification session persistence failed: requestId={}", requestId);
            return auditRoutingTerminal(new QueryResult(QueryStatus.ERROR,
                    null, null, null, snapshotVersion,
                    ErrorCode.PROTOCOL_ERROR.name(),
                    "Clarification session unavailable"),
                    principal, requestId, snapshotVersion, null, startTime);
        }
        log.info("Clarification session created: interactionId={}, candidates={}",
                interactionId, candidateIds);

        return auditRoutingTerminal(new QueryResult(QueryStatus.CLARIFICATION_REQUIRED,
                null, question, interactionId, snapshotVersion,
                ErrorCode.CLARIFICATION_REQUIRED.name(), null,
                null, null, expiresAt), principal, requestId,
                snapshotVersion, null, startTime);
    }

    private QueryResult auditRoutingTerminal(QueryResult result, Principal principal,
                                             String requestId, long snapshotVersion,
                                             CapabilityManifest manifest, long startTime) {
        String subjectDigest = principal == null ? null : computePrincipalDigest(principal);
        long orgId = principal == null ? 0L : principal.orgId();
        String resultCode = result.errorCode() != null
                ? result.errorCode() : result.status().name();
        auditPort.recordEvent(new AuditEvent(
                UUID.randomUUID().toString(), "ROUTING_TERMINAL", Instant.now(),
                subjectDigest, orgId, requestId, null,
                manifest == null ? null : manifest.metadata().id(),
                manifest == null ? null : manifest.metadata().version(),
                manifest == null ? null : ManifestDigest.sha256(manifest),
                snapshotVersion, null, null, resultCode,
                System.currentTimeMillis() - startTime, "{}"));
        return result;
    }

    private String safeLlmErrorMessage(ErrorCode errorCode) {
        return errorCode == ErrorCode.RATE_LIMITED
                ? "LLM provider rate limit reached"
                : "LLM routing unavailable";
    }

    private Map<String, Object> capabilityMetadata(CapabilityManifest manifest) {
        return Map.of(
                "id", manifest.metadata().id(),
                "version", manifest.metadata().version());
    }

    /**
     * Computes a SHA-256 digest of the Principal's subject identifier.
     *
     * @param principal the authenticated principal
     * @return the hex-encoded SHA-256 digest
     */
    private String computePrincipalDigest(Principal principal) {
        return Sha256Digest.sha256Hex(principal.subject());
    }

    /**
     * The status of a natural-language query result.
     */
    public enum QueryStatus {
        /** A capability was selected and routing completed successfully. */
        COMPLETED,
        /** The model or threshold evaluator requires user clarification. */
        CLARIFICATION_REQUIRED,
        /** No capability matched the request. */
        NO_MATCH,
        /** An error occurred during routing. */
        ERROR
    }

    /**
     * The result of a natural-language query execution.
     *
     * @param status the query status
     * @param data the result data; non-null for COMPLETED, null otherwise
     * @param summary a human-readable summary; may be null
     * @param interactionId the clarification interaction ID; non-null for CLARIFICATION_REQUIRED
     * @param snapshotVersion the catalog snapshot version used for routing
     * @param errorCode the stable error code; null on success
     * @param errorMessage the error message; null on success
     */
    public record QueryResult(
            QueryStatus status,
            Map<String, Object> data,
            String summary,
            String interactionId,
            long snapshotVersion,
            String errorCode,
            String errorMessage,
            Map<String, Object> capability,
            Map<String, Object> execution,
            Instant expiresAt
    ) {
        public QueryResult(QueryStatus status, Map<String, Object> data,
                           String summary, String interactionId,
                           long snapshotVersion, String errorCode,
                           String errorMessage) {
            this(status, data, summary, interactionId, snapshotVersion,
                    errorCode, errorMessage, null, null, null);
        }

        public QueryResult {
            data = data == null ? null : Map.copyOf(data);
            capability = capability == null ? null : Map.copyOf(capability);
            execution = execution == null ? null : Map.copyOf(execution);
        }
    }
}
