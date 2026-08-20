package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.application.catalog.CatalogBoundCandidateRetriever;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityReference;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.TextNormalizer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Host-only capability resolver for the fixed gateway_call model contract. */
public final class AgentCapabilityResolver {

    private static final int MAX_CANDIDATES = 5;
    private static final int RECALL_CANDIDATES = 20;
    private static final int MAX_CANDIDATE_CONTEXT_BYTES = 2 * 1024;
    private static final double HIGH_CONFIDENCE_DELTA = 1.0d;

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final InMemoryCatalogManager catalogManager;
    private final CandidateRetriever candidateRetriever;
    private final TextNormalizer textNormalizer;
    private final ToolReferenceService toolReferenceService;
    private final TelemetryPort telemetry;
    private final Executor resolveExecutor;
    private final long resolveTimeoutNanos;

    public AgentCapabilityResolver(AuthenticationPort authenticationPort,
                                   AuthorizationPort authorizationPort,
                                   InMemoryCatalogManager catalogManager,
                                   CandidateRetriever candidateRetriever,
                                   TextNormalizer textNormalizer,
                                   ToolReferenceService toolReferenceService,
                                   TelemetryPort telemetry) {
        this(authenticationPort, authorizationPort, catalogManager, candidateRetriever,
                textNormalizer, toolReferenceService, telemetry,
                ForkJoinPool.commonPool(), 100L);
    }

    public AgentCapabilityResolver(AuthenticationPort authenticationPort,
                                   AuthorizationPort authorizationPort,
                                   InMemoryCatalogManager catalogManager,
                                   CandidateRetriever candidateRetriever,
                                   TextNormalizer textNormalizer,
                                   ToolReferenceService toolReferenceService,
                                   TelemetryPort telemetry,
                                   Executor resolveExecutor,
                                   long resolveTimeoutMs) {
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
        this.catalogManager = Objects.requireNonNull(catalogManager);
        this.candidateRetriever = Objects.requireNonNull(candidateRetriever);
        this.textNormalizer = Objects.requireNonNull(textNormalizer);
        this.toolReferenceService = Objects.requireNonNull(toolReferenceService);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.resolveExecutor = Objects.requireNonNull(resolveExecutor);
        if (resolveTimeoutMs <= 0) {
            throw new IllegalArgumentException("resolveTimeoutMs must be positive");
        }
        this.resolveTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(resolveTimeoutMs);
    }

    public Resolution resolve(RequestContext requestContext, String query, int requestedTopK) {
        Objects.requireNonNull(requestContext, "requestContext must not be null");
        Principal principal = authenticationPort.authenticate(requestContext);
        return resolve(principal, query, requestedTopK);
    }

    /** Internal Host entry point that reuses the Principal authenticated by the Connector. */
    public Resolution resolve(Principal principal, String query, int requestedTopK) {
        Objects.requireNonNull(principal, "principal must not be null");
        long started = System.nanoTime();
        long deadlineNanos = deadlineAfter(resolveTimeoutNanos);
        String outcome = "error";
        try {
            ActiveCatalogView.ViewLease viewLease = catalogManager.acquireActiveView();
            if (viewLease == null) {
                return Resolution.error("CAPABILITY_UNAVAILABLE", 0L, 0L);
            }
            try {
            ActiveCatalogView view = viewLease.view();
            if (view == null || view.catalogVersion() <= 0) {
                return Resolution.error("CAPABILITY_UNAVAILABLE", 0L, 0L);
            }
            long indexedVersion = candidateRetriever.indexedCatalogVersion();
            if (!(candidateRetriever instanceof CatalogBoundCandidateRetriever)
                    && indexedVersion >= 0 && indexedVersion != view.catalogVersion()) {
                return Resolution.error("CATALOG_INDEX_NOT_READY",
                        view.catalogVersion(), 0L);
            }

            PolicySnapshot policySnapshot = authorizationPort.resolvePolicySnapshot(principal);
            if (policySnapshot == null || !policySnapshot.healthy()
                    || policySnapshot.policyEpoch() <= 0) {
                return Resolution.error("POLICY_UNAVAILABLE", view.catalogVersion(), 0L);
            }
            if (expired(deadlineNanos, "authorization")) {
                outcome = "timeout";
                return Resolution.error("RESOLVE_TIMEOUT", view.catalogVersion(),
                        policySnapshot.policyEpoch());
            }
            CapabilityVisibility visibility = policySnapshot.visibility();
            List<CapabilityManifest> authorized = view.visibleCapabilities(visibility).stream()
                    .filter(manifest -> manifest.spec().risk() != RiskLevel.WRITE_HIGH)
                    .filter(manifest -> view.publicProjection(manifest).isPresent())
                    .toList();

            String normalizedQuery = textNormalizer.normalize(query);
            if (normalizedQuery.isEmpty() || requestedTopK <= 0 || authorized.isEmpty()) {
                outcome = "empty";
                return Resolution.resolved(view.catalogVersion(), visibility.policyEpoch(),
                        List.of(), null, null);
            }

            RetrievalOutcome retrieval = retrieveWithDeadline(
                    normalizedQuery, view, authorized, deadlineNanos);
            if (retrieval.timedOut()) {
                outcome = "timeout";
                return Resolution.error("RESOLVE_TIMEOUT", view.catalogVersion(),
                        visibility.policyEpoch());
            }
            List<CandidateRetriever.ScoredCapability> recalled = retrieval.results();
            if (recalled == null || recalled.isEmpty()) {
                outcome = "empty";
                return Resolution.resolved(view.catalogVersion(), visibility.policyEpoch(),
                        List.of(), null, null);
            }
            if (expired(deadlineNanos, "rerank")) {
                outcome = "timeout";
                return Resolution.error("RESOLVE_TIMEOUT", view.catalogVersion(),
                        visibility.policyEpoch());
            }

            List<RankedCandidate> ranked = recalled.stream()
                    .filter(Objects::nonNull)
                    .filter(scored -> scored.capability() != null)
                    .map(scored -> view.publicProjection(scored.capability())
                            .map(projection -> new RankedCandidate(
                                    scored.capability(), projection,
                                    rerankScore(normalizedQuery, scored, projection)))
                            .orElse(null))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingDouble(RankedCandidate::score).reversed()
                            .thenComparing(candidate ->
                                    candidate.manifest().metadata().id())
                            .thenComparing(candidate ->
                                    candidate.manifest().metadata().version()))
                    .toList();

            int limit = Math.min(Math.max(requestedTopK, 1), MAX_CANDIDATES);
            List<Candidate> candidates = new ArrayList<>(limit);
            List<RankedCandidate> included = new ArrayList<>(limit);
            int contextBytes = 0;
            Instant earliestExpiry = null;
            for (RankedCandidate rankedCandidate : ranked) {
                if (expired(deadlineNanos, "reference")) {
                    outcome = "timeout";
                    return Resolution.error("RESOLVE_TIMEOUT", view.catalogVersion(),
                            visibility.policyEpoch());
                }
                if (candidates.size() >= limit) {
                    break;
                }
                ToolReferenceService.IssuedReference issued = toolReferenceService.issue(
                        principal, rankedCandidate.manifest(), view.catalogVersion(),
                        visibility.policyEpoch());
                Candidate candidate = toCandidate(issued.toolRef(), rankedCandidate);
                int candidateBytes = estimatedBytes(candidate);
                if (!candidates.isEmpty()
                        && contextBytes + candidateBytes > MAX_CANDIDATE_CONTEXT_BYTES) {
                    break;
                }
                candidates.add(candidate);
                included.add(rankedCandidate);
                contextBytes += candidateBytes;
                if (earliestExpiry == null || issued.expiresAt().isBefore(earliestExpiry)) {
                    earliestExpiry = issued.expiresAt();
                }
            }

            SelectedSchema selectedSchema = selectedSchema(candidates, included, normalizedQuery);
            outcome = candidates.isEmpty() ? "empty" : "resolved";
            telemetry.increment("gateway.agent.resolve.candidates", Map.of(
                    "resource", candidateBucket(candidates.size()), "outcome", outcome));
            return Resolution.resolved(view.catalogVersion(), visibility.policyEpoch(),
                    candidates, selectedSchema, earliestExpiry);
            } finally {
                viewLease.close();
            }
        } finally {
            telemetry.recordDuration("gateway.agent.resolve.duration", System.nanoTime() - started,
                    Map.of("resource", "resolve", "outcome", outcome));
        }
    }

    private RetrievalOutcome retrieveWithDeadline(
            String normalizedQuery,
            ActiveCatalogView view,
            List<CapabilityManifest> authorized,
            long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            expired(deadlineNanos, "retrieval");
            return RetrievalOutcome.timeout();
        }
        CompletableFuture<List<CandidateRetriever.ScoredCapability>> future =
                CompletableFuture.supplyAsync(() -> candidateRetriever
                        instanceof CatalogBoundCandidateRetriever boundRetriever
                        ? boundRetriever.retrieve(normalizedQuery, view, authorized,
                                RECALL_CANDIDATES)
                        : candidateRetriever.retrieve(normalizedQuery, authorized,
                                RECALL_CANDIDATES), resolveExecutor);
        try {
            return new RetrievalOutcome(
                    future.get(remainingNanos, TimeUnit.NANOSECONDS), false);
        } catch (TimeoutException e) {
            future.cancel(true);
            expired(deadlineNanos, "retrieval");
            return RetrievalOutcome.timeout();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            expired(deadlineNanos, "retrieval");
            return RetrievalOutcome.timeout();
        } catch (java.util.concurrent.ExecutionException e) {
            return new RetrievalOutcome(List.of(), false);
        }
    }

    private boolean expired(long deadlineNanos, String phase) {
        if (System.nanoTime() <= deadlineNanos) {
            return false;
        }
        telemetry.increment("gateway.agent.resolve.timeout", Map.of("outcome", phase));
        return true;
    }

    private static long deadlineAfter(long timeoutNanos) {
        long now = System.nanoTime();
        long deadline = now + timeoutNanos;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    public SchemaResult loadSchema(RequestContext requestContext, String toolRef) {
        Objects.requireNonNull(requestContext, "requestContext must not be null");
        Principal principal = authenticationPort.authenticate(requestContext);
        return loadSchema(principal, toolRef);
    }

    /** Internal Host entry point that reuses the Principal authenticated by the Connector. */
    public SchemaResult loadSchema(Principal principal, String toolRef) {
        Objects.requireNonNull(principal, "principal must not be null");
        long started = System.nanoTime();
        String outcome = "error";
        try {
            ActiveCatalogView.ViewLease viewLease = catalogManager.acquireActiveView();
            if (viewLease == null) {
                return SchemaResult.error("CAPABILITY_UNAVAILABLE");
            }
            try {
            ActiveCatalogView view = viewLease.view();
            if (view == null) {
                return SchemaResult.error("CAPABILITY_UNAVAILABLE");
            }
            PolicySnapshot policySnapshot = authorizationPort.resolvePolicySnapshot(principal);
            if (policySnapshot == null || !policySnapshot.healthy()
                    || policySnapshot.policyEpoch() <= 0) {
                return SchemaResult.error("POLICY_UNAVAILABLE");
            }
            ToolReferenceService.Verification verification = toolReferenceService.verify(
                    toolRef, principal, view, policySnapshot.policyEpoch());
            if (!verification.valid()) {
                return SchemaResult.error(errorCode(verification.failure()));
            }
            if (!isVisible(policySnapshot.visibility(), view, verification.manifest())) {
                return SchemaResult.error("CAPABILITY_UNAVAILABLE");
            }
            Optional<CapabilityPublicProjectionService.Projection> projection =
                    view.publicProjection(verification.manifest());
            if (projection.isEmpty() || projection.get().publicSchema().isEmpty()) {
                return SchemaResult.error("SCHEMA_REQUIRES_INTERACTIVE_FLOW");
            }
            outcome = "completed";
            return SchemaResult.completed(toolRef, projection.get().schemaClass(),
                    projection.get().publicSchema(), verification.expiresAt());
            } finally {
                viewLease.close();
            }
        } finally {
            telemetry.recordDuration("gateway.agent.schema.duration", System.nanoTime() - started,
                    Map.of("resource", "schema", "outcome", outcome));
        }
    }

    private boolean isVisible(
            CapabilityVisibility visibility, ActiveCatalogView view,
            CapabilityManifest manifest) {
        CapabilityReference expected = CapabilityReference.from(manifest);
        return view.visibleCapabilities(visibility).stream()
                .map(CapabilityReference::from)
                .anyMatch(expected::equals);
    }

    private SelectedSchema selectedSchema(
            List<Candidate> candidates,
            List<RankedCandidate> included,
            String normalizedQuery) {
        if (candidates.isEmpty() || included.isEmpty()) {
            return null;
        }
        RankedCandidate first = included.get(0);
        if (first.projection().schemaClass()
                != CapabilityPublicProjectionService.SchemaClass.STANDARD) {
            return null;
        }
        boolean exactName = normalizedQuery.equals(
                textNormalizer.normalize(first.projection().displayName()));
        boolean separated = included.size() == 1
                || first.score() - included.get(1).score() >= HIGH_CONFIDENCE_DELTA;
        if (!exactName && !separated) {
            return null;
        }
        return new SelectedSchema(candidates.get(0).toolRef(), first.projection().publicSchema());
    }

    private double rerankScore(
            String normalizedQuery,
            CandidateRetriever.ScoredCapability scored,
            CapabilityPublicProjectionService.Projection projection) {
        String normalizedName = textNormalizer.normalize(projection.displayName());
        double score = scored.score();
        if (normalizedQuery.equals(normalizedName)) {
            score += 10.0d;
        } else if (!normalizedName.isEmpty() && normalizedQuery.contains(normalizedName)) {
            score += 3.0d;
        }
        if (scored.capability().spec().risk() == RiskLevel.READ_ONLY) {
            score += 0.05d;
        }
        if (projection.schemaClass()
                == CapabilityPublicProjectionService.SchemaClass.COMPLEX) {
            score -= 0.05d;
        }
        return score;
    }

    private static Candidate toCandidate(String toolRef, RankedCandidate candidate) {
        return new Candidate(
                toolRef,
                candidate.projection().displayName(),
                candidate.projection().purpose(),
                candidate.projection().schemaClass(),
                candidate.projection().argumentContract(),
                candidate.manifest().spec().risk() == RiskLevel.READ_ONLY
                        ? "DIRECT" : "CONFIRMATION_REQUIRED");
    }

    private static int estimatedBytes(Candidate candidate) {
        return candidate.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static String candidateBucket(int count) {
        return count <= 0 ? "0" : count == 1 ? "1" : count <= 3 ? "2_3" : "4_5";
    }

    private static String errorCode(ToolReferenceService.Failure failure) {
        return switch (failure) {
            case EXPIRED -> "TOOL_REF_EXPIRED";
            case CATALOG_CHANGED -> "CATALOG_CHANGED";
            case POLICY_CHANGED -> "POLICY_CHANGED";
            case MALFORMED, SIGNATURE_INVALID, PRINCIPAL_MISMATCH, CAPABILITY_UNAVAILABLE ->
                    "CAPABILITY_UNAVAILABLE";
        };
    }

    public enum Status { RESOLVED, ERROR }

    public record Resolution(
            Status status,
            String errorCode,
            long catalogVersion,
            long policyEpoch,
            List<Candidate> candidates,
            SelectedSchema selectedSchema,
            Instant expiresAt) {

        private static Resolution resolved(
                long catalogVersion,
                long policyEpoch,
                List<Candidate> candidates,
                SelectedSchema selectedSchema,
                Instant expiresAt) {
            return new Resolution(Status.RESOLVED, null, catalogVersion, policyEpoch,
                    List.copyOf(candidates), selectedSchema, expiresAt);
        }

        private static Resolution error(
                String errorCode, long catalogVersion, long policyEpoch) {
            return new Resolution(Status.ERROR, errorCode, catalogVersion, policyEpoch,
                    List.of(), null, null);
        }
    }

    public record Candidate(
            String toolRef,
            String displayName,
            String purpose,
            CapabilityPublicProjectionService.SchemaClass schemaClass,
            Map<String, Object> argumentContract,
            String executionMode) {
    }

    public record SelectedSchema(String toolRef, Map<String, Object> inputSchema) {
    }

    public record SchemaResult(
            Status status,
            String errorCode,
            String toolRef,
            CapabilityPublicProjectionService.SchemaClass schemaClass,
            Map<String, Object> inputSchema,
            Instant expiresAt) {

        private static SchemaResult completed(
                String toolRef,
                CapabilityPublicProjectionService.SchemaClass schemaClass,
                Map<String, Object> inputSchema,
                Instant expiresAt) {
            return new SchemaResult(Status.RESOLVED, null, toolRef, schemaClass,
                    Map.copyOf(inputSchema), expiresAt);
        }

        private static SchemaResult error(String errorCode) {
            return new SchemaResult(Status.ERROR, errorCode, null, null, Map.of(), null);
        }
    }

    private record RankedCandidate(
            CapabilityManifest manifest,
            CapabilityPublicProjectionService.Projection projection,
            double score) {
    }

    private record RetrievalOutcome(
            List<CandidateRetriever.ScoredCapability> results,
            boolean timedOut) {
        private static RetrievalOutcome timeout() {
            return new RetrievalOutcome(List.of(), true);
        }
    }
}
