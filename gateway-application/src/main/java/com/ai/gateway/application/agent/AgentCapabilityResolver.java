package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.application.catalog.AgentCandidateRanker;
import com.ai.gateway.application.catalog.AuthorizedCandidateRetrieval;
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
import com.ai.gateway.domain.port.TelemetryPort;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Host-only capability resolver for the fixed gateway_call model contract. */
public final class AgentCapabilityResolver {

    private static final int MAX_CANDIDATES = 5;
    private static final int RECALL_CANDIDATES = 20;
    private static final int MAX_CANDIDATE_CONTEXT_BYTES = 2 * 1024;
    private static final double HIGH_CONFIDENCE_DELTA = 1.0d;
    private static final Executor DEFAULT_RESOLVE_EXECUTOR = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            runnable -> {
                Thread thread = new Thread(runnable, "gateway-agent-resolve-default");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final InMemoryCatalogManager catalogManager;
    private final AuthorizedCandidateRetrieval candidateRetrieval;
    private final AgentCandidateRanker candidateRanker;
    private final ToolReferenceService toolReferenceService;
    private final TelemetryPort telemetry;
    private final Executor resolveExecutor;
    private final long resolveTimeoutNanos;

    public AgentCapabilityResolver(AuthenticationPort authenticationPort,
                                   AuthorizationPort authorizationPort,
                                   InMemoryCatalogManager catalogManager,
                                   AuthorizedCandidateRetrieval candidateRetrieval,
                                   AgentCandidateRanker candidateRanker,
                                   ToolReferenceService toolReferenceService,
                                   TelemetryPort telemetry) {
        this(authenticationPort, authorizationPort, catalogManager, candidateRetrieval,
                candidateRanker, toolReferenceService, telemetry,
                DEFAULT_RESOLVE_EXECUTOR, 100L);
    }

    public AgentCapabilityResolver(AuthenticationPort authenticationPort,
                                   AuthorizationPort authorizationPort,
                                   InMemoryCatalogManager catalogManager,
                                   AuthorizedCandidateRetrieval candidateRetrieval,
                                   AgentCandidateRanker candidateRanker,
                                   ToolReferenceService toolReferenceService,
                                   TelemetryPort telemetry,
                                   Executor resolveExecutor,
                                   long resolveTimeoutMs) {
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
        this.catalogManager = Objects.requireNonNull(catalogManager);
        this.candidateRetrieval = Objects.requireNonNull(candidateRetrieval);
        this.candidateRanker = Objects.requireNonNull(candidateRanker);
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
        long deadlineNanos = newResolveDeadlineNanos();
        AuthenticationResult authentication = authenticate(requestContext, deadlineNanos);
        if (authentication.timedOut()) {
            return Resolution.error("RESOLVE_TIMEOUT", 0L, 0L);
        }
        if (authentication.capacityRejected()) {
            return Resolution.error("RESOLVE_CAPACITY_EXCEEDED", 0L, 0L);
        }
        if (authentication.status() == AuthenticationStatus.FAILED) {
            if (authentication.failure() != null) {
                throw propagate(authentication.failure());
            }
            return Resolution.error("AUTHENTICATION_FAILED", 0L, 0L);
        }
        if (authentication.principal() == null) {
            return Resolution.error("AUTHENTICATION_FAILED", 0L, 0L);
        }
        return resolve(authentication.principal(), query, requestedTopK, deadlineNanos);
    }

    /** Internal Host entry point that reuses the Principal authenticated by the Connector. */
    public Resolution resolve(Principal principal, String query, int requestedTopK) {
        return resolve(principal, query, requestedTopK, newResolveDeadlineNanos());
    }

    /**
     * Internal Host entry point with a deadline created before authentication.
     * The Connector uses this overload so authentication time is part of the
     * same Resolve budget as policy, retrieval, reranking, and reference issue.
     */
    public Resolution resolve(
            Principal principal, String query, int requestedTopK, long deadlineNanos) {
        Objects.requireNonNull(principal, "principal must not be null");
        long started = System.nanoTime();
        String outcome = "error";
        try {
            if (expired(deadlineNanos, "authentication")) {
                outcome = "timeout";
                return Resolution.error("RESOLVE_TIMEOUT", 0L, 0L);
            }
            ActiveCatalogView.ViewLease viewLease = catalogManager.acquireActiveView();
            if (viewLease == null) {
                return Resolution.error("CAPABILITY_UNAVAILABLE", 0L, 0L);
            }
            try {
            ActiveCatalogView view = viewLease.view();
            if (view == null || view.catalogVersion() <= 0) {
                return Resolution.error("CAPABILITY_UNAVAILABLE", 0L, 0L);
            }
            // 索引漂移只在「检索不绑定本次视图」时才需要判定：绑定视图的检索器天然与
            // view.catalogVersion() 同版本，此时读全局索引版本反而会把一次正常的目录切换
            // 误判成不可用。该判定条件由检索内核统一给出，不再由本类推断检索器类型。
            long indexedVersion = candidateRetrieval.indexedCatalogVersion();
            if (!candidateRetrieval.viewBound()
                    && indexedVersion >= 0 && indexedVersion != view.catalogVersion()) {
                return Resolution.error("CATALOG_INDEX_NOT_READY",
                        view.catalogVersion(), 0L);
            }

            DeadlineCall<PolicySnapshot> policyCall = callWithDeadline(
                    () -> authorizationPort.resolvePolicySnapshot(principal),
                    deadlineNanos, "authorization");
            if (policyCall.rejected()) {
                outcome = "capacity_rejected";
                return Resolution.error("RESOLVE_CAPACITY_EXCEEDED",
                        view.catalogVersion(), 0L);
            }
            if (policyCall.timedOut()) {
                outcome = "timeout";
                return Resolution.error("RESOLVE_TIMEOUT", view.catalogVersion(), 0L);
            }
            if (policyCall.failure() != null) {
                throw propagate(policyCall.failure());
            }
            PolicySnapshot policySnapshot = policyCall.value();
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
            if (requestedTopK <= 0) {
                // 调用方要求 0 个候选，无需占用检索内核的准入名额。
                outcome = "empty";
                return Resolution.resolved(view.catalogVersion(), visibility.policyEpoch(),
                        List.of(), null, null);
            }

            // 收窄检索域、归一化与 BM25 召回全部交由共用内核执行：WRITE_HIGH 排除写在内核里，
            // 本类只补充自身承载面的附加条件（必须存在公开投影，否则没有可展示的字段）。
            RetrievalOutcome retrieval = retrieveWithDeadline(
                    query, view, view.visibleCapabilities(visibility), deadlineNanos);
            if (retrieval.timedOut()) {
                outcome = "timeout";
                return Resolution.error("RESOLVE_TIMEOUT", view.catalogVersion(),
                        visibility.policyEpoch());
            }
            if (retrieval.capacityRejected()) {
                outcome = "capacity_rejected";
                return Resolution.error("RESOLVE_CAPACITY_EXCEEDED",
                        view.catalogVersion(), visibility.policyEpoch());
            }
            // 空检索域、空归一化文本、检索失败与零命中在本平面是同一种对外结果：空候选集。
            // 刻意不区分——区分即等于告知调用方「有能力但你看不到」。
            AuthorizedCandidateRetrieval.Retrieved retrieved = retrieval.retrieved();
            if (retrieved == null || retrieved.candidates().isEmpty()) {
                outcome = "empty";
                return Resolution.resolved(view.catalogVersion(), visibility.policyEpoch(),
                        List.of(), null, null);
            }
            if (expired(deadlineNanos, "rerank")) {
                outcome = "timeout";
                return Resolution.error("RESOLVE_TIMEOUT", view.catalogVersion(),
                        visibility.policyEpoch());
            }

            List<AgentCandidateRanker.Ranked> ranked = candidateRanker.rank(
                    retrieved.normalizedText(), view, retrieved.candidates());

            int limit = Math.min(Math.max(requestedTopK, 1), MAX_CANDIDATES);
            List<Candidate> candidates = new ArrayList<>(limit);
            List<AgentCandidateRanker.Ranked> included = new ArrayList<>(limit);
            int contextBytes = 0;
            Instant earliestExpiry = null;
            for (AgentCandidateRanker.Ranked rankedCandidate : ranked) {
                if (expired(deadlineNanos, "reference")) {
                    outcome = "timeout";
                    return Resolution.error("RESOLVE_TIMEOUT", view.catalogVersion(),
                            visibility.policyEpoch());
                }
                if (candidates.size() >= limit) {
                    break;
                }
                ToolReferenceService.IssuedReference issued = toolReferenceService.issue(
                        principal, rankedCandidate.capability(), view.catalogVersion(),
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

            SelectedSchema selectedSchema = selectedSchema(candidates, included);
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

    /**
     * 在 Resolve 预算内执行一次授权域检索。
     *
     * <p>整段（收窄检索域 → 归一化 → BM25）都放进受限线程池里执行，而不是先在调用线程上
     * 判断「文本是否为空」再决定是否提交：把空文本排除在准入控制之外，等于给出一条
     * 无需名额即可打到网关的路径。</p>
     */
    private RetrievalOutcome retrieveWithDeadline(
            String rawQuery,
            ActiveCatalogView view,
            List<CapabilityManifest> visible,
            long deadlineNanos) {
        DeadlineCall<AuthorizedCandidateRetrieval.Retrieved> retrievalCall = callWithDeadline(
                () -> candidateRetrieval.retrieveWithinAgentScope(rawQuery, view, visible,
                        manifest -> view.publicProjection(manifest).isPresent(),
                        RECALL_CANDIDATES),
                deadlineNanos, "retrieval");
        if (retrievalCall.rejected()) {
            return RetrievalOutcome.rejectedOutcome();
        }
        if (retrievalCall.timedOut()) {
            return RetrievalOutcome.timeout();
        }
        if (retrievalCall.failure() != null) {
            return RetrievalOutcome.empty();
        }
        return new RetrievalOutcome(retrievalCall.value(), false, false);
    }

    private <T> DeadlineCall<T> callWithDeadline(
            Supplier<T> operation, long deadlineNanos, String phase) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            expired(deadlineNanos, phase);
            return DeadlineCall.timeoutCall();
        }
        CompletableFuture<T> future;
        try {
            future = CompletableFuture.supplyAsync(operation, resolveExecutor);
        } catch (RejectedExecutionException e) {
            telemetry.increment("gateway.agent.resolve.admission",
                    Map.of("outcome", "capacity_rejected"));
            return DeadlineCall.rejectedCall();
        }
        try {
            return DeadlineCall.completedCall(
                    future.get(remainingNanos, TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            cancel(future);
            expired(deadlineNanos, phase);
            return DeadlineCall.timeoutCall();
        } catch (InterruptedException e) {
            cancel(future);
            Thread.currentThread().interrupt();
            expired(deadlineNanos, phase);
            return DeadlineCall.timeoutCall();
        } catch (java.util.concurrent.ExecutionException e) {
            return DeadlineCall.failedCall(e.getCause());
        }
    }

    private void cancel(CompletableFuture<?> future) {
        future.cancel(true);
        if (resolveExecutor instanceof java.util.concurrent.ThreadPoolExecutor executor) {
            executor.purge();
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(failure);
    }

    public long newResolveDeadlineNanos() {
        return deadlineAfter(resolveTimeoutNanos);
    }

    public AuthenticationResult authenticate(
            RequestContext requestContext, long deadlineNanos) {
        Objects.requireNonNull(requestContext, "requestContext must not be null");
        DeadlineCall<Principal> authenticationCall = callWithDeadline(
                () -> authenticationPort.authenticate(requestContext),
                deadlineNanos, "authentication");
        if (authenticationCall.rejected()) {
            return AuthenticationResult.capacityResult();
        }
        if (authenticationCall.timedOut()) {
            return AuthenticationResult.timeout();
        }
        if (authenticationCall.failure() != null) {
            return AuthenticationResult.failed(authenticationCall.failure());
        }
        if (authenticationCall.value() == null) {
            return AuthenticationResult.failed(null);
        }
        return AuthenticationResult.authenticated(authenticationCall.value());
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

    /**
     * 判定是否可以随候选一起直接给出唯一 schema。
     *
     * <p>「查询与展示名完全一致」这一结论直接取自重排器（{@link AgentCandidateRanker.Ranked#exactNameMatch()}），
     * 不在这里重算一遍归一化比较：同一个判定写两处，两处早晚会漂移，而漂移的表现是
     * 「有时给 schema、有时不给」这种无法归因的间歇行为。</p>
     */
    private SelectedSchema selectedSchema(
            List<Candidate> candidates,
            List<AgentCandidateRanker.Ranked> included) {
        if (candidates.isEmpty() || included.isEmpty()) {
            return null;
        }
        AgentCandidateRanker.Ranked first = included.get(0);
        if (first.projection().schemaClass()
                != CapabilityPublicProjectionService.SchemaClass.STANDARD) {
            return null;
        }
        boolean separated = included.size() == 1
                || first.rankScore() - included.get(1).rankScore() >= HIGH_CONFIDENCE_DELTA;
        if (!first.exactNameMatch() && !separated) {
            return null;
        }
        return new SelectedSchema(candidates.get(0).toolRef(), first.projection().publicSchema());
    }

    private static Candidate toCandidate(String toolRef, AgentCandidateRanker.Ranked candidate) {
        return new Candidate(
                toolRef,
                candidate.projection().displayName(),
                candidate.projection().purpose(),
                candidate.projection().schemaClass(),
                candidate.projection().argumentContract(),
                candidate.capability().spec().risk() == RiskLevel.READ_ONLY
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

    public record AuthenticationResult(
            AuthenticationStatus status, Principal principal, Throwable failure) {

        private static AuthenticationResult authenticated(Principal principal) {
            return new AuthenticationResult(AuthenticationStatus.AUTHENTICATED, principal, null);
        }

        private static AuthenticationResult timeout() {
            return new AuthenticationResult(AuthenticationStatus.TIMEOUT, null, null);
        }

        private static AuthenticationResult capacityResult() {
            return new AuthenticationResult(
                    AuthenticationStatus.CAPACITY_EXCEEDED, null, null);
        }

        private static AuthenticationResult failed(Throwable failure) {
            return new AuthenticationResult(AuthenticationStatus.FAILED, null, failure);
        }

        public boolean timedOut() {
            return status == AuthenticationStatus.TIMEOUT;
        }

        public boolean capacityRejected() {
            return status == AuthenticationStatus.CAPACITY_EXCEEDED;
        }
    }

    public enum AuthenticationStatus {
        AUTHENTICATED, FAILED, TIMEOUT, CAPACITY_EXCEEDED
    }

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

    private record RetrievalOutcome(
            AuthorizedCandidateRetrieval.Retrieved retrieved,
            boolean timedOut,
            boolean capacityRejected) {
        private static RetrievalOutcome timeout() {
            return new RetrievalOutcome(null, true, false);
        }

        private static RetrievalOutcome rejectedOutcome() {
            return new RetrievalOutcome(null, false, true);
        }

        /** 检索任务本身异常终止：与「零命中」同等对待，绝不把内部故障文本带给调用方。 */
        private static RetrievalOutcome empty() {
            return new RetrievalOutcome(null, false, false);
        }
    }

    private record DeadlineCall<T>(
            T value,
            boolean timedOut,
            boolean rejected,
            Throwable failure) {
        private static <T> DeadlineCall<T> completedCall(T value) {
            return new DeadlineCall<>(value, false, false, null);
        }

        private static <T> DeadlineCall<T> timeoutCall() {
            return new DeadlineCall<>(null, true, false, null);
        }

        private static <T> DeadlineCall<T> rejectedCall() {
            return new DeadlineCall<>(null, false, true, null);
        }

        private static <T> DeadlineCall<T> failedCall(Throwable failure) {
            return new DeadlineCall<>(null, false, false, failure);
        }
    }
}
