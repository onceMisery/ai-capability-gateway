package com.ai.gateway.application.runtime;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.NlInteraction;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RoutingThresholds;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.InteractionRepository;
import com.ai.gateway.domain.service.ManifestDigest;
import com.ai.gateway.domain.service.Sha256Digest;
import com.ai.gateway.domain.service.TextNormalizer;
import com.ai.gateway.domain.service.ThresholdEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * <p>SELECT 后处理由 {@link SelectDecisionProcessor} 负责，本类只保留认证、
 * 快照、检索、阈值和澄清流程编排。</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is thread-safe: it holds no mutable per-request state.</p>
 *
 * @see AuthenticationPort
 * @see AuthorizationPort
 * @see CandidateRetriever
 * @see ThresholdEvaluator
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
    private final AuditPort auditPort;
    private final ThresholdEvaluator thresholdEvaluator;
    private final TextNormalizer textNormalizer;
    private final InteractionRepository interactionRepository;
    private final String environment;
    private final SelectDecisionProcessor selectDecisionProcessor;

    public NaturalLanguageQueryUseCase(AuthenticationPort authenticationPort,
                                        AuthorizationPort authorizationPort,
                                        CatalogPort catalogPort,
                                        CandidateRetriever candidateRetriever,
                                        AuditPort auditPort,
                                        ThresholdEvaluator thresholdEvaluator,
                                        TextNormalizer textNormalizer,
                                        InteractionRepository interactionRepository,
                                        String environment,
                                        SelectDecisionProcessor selectDecisionProcessor) {
        this.authenticationPort = java.util.Objects.requireNonNull(authenticationPort,
                "authenticationPort must not be null");
        this.authorizationPort = java.util.Objects.requireNonNull(authorizationPort,
                "authorizationPort must not be null");
        this.catalogPort = java.util.Objects.requireNonNull(catalogPort,
                "catalogPort must not be null");
        this.candidateRetriever = java.util.Objects.requireNonNull(candidateRetriever,
                "candidateRetriever must not be null");
        this.auditPort = java.util.Objects.requireNonNull(auditPort,
                "auditPort must not be null");
        this.thresholdEvaluator = java.util.Objects.requireNonNull(thresholdEvaluator,
                "thresholdEvaluator must not be null");
        this.interactionRepository = java.util.Objects.requireNonNull(interactionRepository,
                "interactionRepository must not be null");
        this.textNormalizer = java.util.Objects.requireNonNull(textNormalizer,
                "textNormalizer must not be null");
        this.environment = java.util.Objects.requireNonNull(environment,
                "environment must not be null");
        this.selectDecisionProcessor = java.util.Objects.requireNonNull(
                selectDecisionProcessor, "selectDecisionProcessor must not be null");
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
        return selectDecisionProcessor.process(
                selected, principal, snapshotVersion, requestId, originalText, locale, startTime,
                new SelectDecisionProcessor.TerminalRecorder() {
                    @Override
                    public QueryResult record(QueryResult result,
                                              Principal terminalPrincipal,
                                              String terminalRequestId,
                                              long terminalSnapshotVersion,
                                              CapabilityManifest manifest,
                                              long terminalStartTime) {
                        return auditRoutingTerminal(result, terminalPrincipal, terminalRequestId,
                                terminalSnapshotVersion, manifest, terminalStartTime);
                    }

                    @Override
                    public QueryResult clarification(
                            List<CandidateRetriever.ScoredCapability> candidates,
                            String question,
                            Principal terminalPrincipal,
                            long terminalSnapshotVersion,
                            String terminalRequestId,
                            long terminalStartTime) {
                        return initiateClarification(candidates, question, terminalPrincipal,
                                terminalSnapshotVersion, terminalRequestId, terminalStartTime);
                    }
                });
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
