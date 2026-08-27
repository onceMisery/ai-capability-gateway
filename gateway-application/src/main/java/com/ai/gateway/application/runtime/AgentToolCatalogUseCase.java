package com.ai.gateway.application.runtime;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.application.catalog.AgentCandidateRanker;
import com.ai.gateway.application.catalog.AuthorizedCandidateRetrieval;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.service.AliasGenerator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves a small, request-scoped tool catalog for a trusted Agent Host.
 *
 * <p>The authorization pass happens before BM25 retrieval. The model-facing
 * result contains only public capability data and short aliases; the host
 * keeps the real capability binding separately.</p>
 *
 * <p>检索与重排本身不在这里实现：本用例与 Host 侧 capability resolve 共用
 * {@link AuthorizedCandidateRetrieval} 与 {@link AgentCandidateRanker}，因此同一 Principal、
 * 同一 query 在两个入口得到同一候选集合与同一排序。本类只保留自己独有的部分——
 * schema 体积预算、别名签发与 Host 侧绑定表。</p>
 */
public final class AgentToolCatalogUseCase {

    private static final int DEFAULT_MAX_CANDIDATES = 5;
    private static final int DEFAULT_RECALL_CANDIDATES = 20;
    private static final int DEFAULT_SCHEMA_BUDGET_BYTES = 16 * 1024;

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final Supplier<ActiveCatalogView> activeViewProvider;
    private final AuthorizedCandidateRetrieval candidateRetrieval;
    private final AgentCandidateRanker candidateRanker;
    private final AliasGenerator aliasGenerator;
    private final int maxCandidates;
    private final int schemaBudgetBytes;

    public AgentToolCatalogUseCase(AuthenticationPort authenticationPort,
                                   AuthorizationPort authorizationPort,
                                   InMemoryCatalogManager catalogManager,
                                   AuthorizedCandidateRetrieval candidateRetrieval,
                                   AgentCandidateRanker candidateRanker,
                                   AliasGenerator aliasGenerator,
                                   String environment) {
        this(authenticationPort, authorizationPort, catalogManager::getActiveView,
                candidateRetrieval, candidateRanker, aliasGenerator, environment,
                DEFAULT_MAX_CANDIDATES, DEFAULT_SCHEMA_BUDGET_BYTES);
    }


    AgentToolCatalogUseCase(AuthenticationPort authenticationPort,
                            AuthorizationPort authorizationPort,
                            Supplier<ActiveCatalogView> activeViewProvider,
                            AuthorizedCandidateRetrieval candidateRetrieval,
                            AgentCandidateRanker candidateRanker,
                            AliasGenerator aliasGenerator,
                            String environment,
                            int maxCandidates,
                            int schemaBudgetBytes) {
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
        this.activeViewProvider = Objects.requireNonNull(activeViewProvider);
        this.candidateRetrieval = Objects.requireNonNull(candidateRetrieval);
        this.candidateRanker = Objects.requireNonNull(candidateRanker);
        this.aliasGenerator = Objects.requireNonNull(aliasGenerator);
        requireText(environment, "environment");
        if (maxCandidates <= 0) {
            throw new IllegalArgumentException("maxCandidates must be positive");
        }
        if (schemaBudgetBytes <= 0) {
            throw new IllegalArgumentException("schemaBudgetBytes must be positive");
        }
        this.maxCandidates = maxCandidates;
        this.schemaBudgetBytes = schemaBudgetBytes;
    }

    /**
     * Resolves at most the configured number of model-visible tools.
     *
     * @param requestContext caller credentials and request metadata
     * @param query natural-language intent
     * @param requestedTopK caller requested limit, capped by the gateway
     */
    public Resolution resolve(RequestContext requestContext, String query, int requestedTopK) {
        Objects.requireNonNull(requestContext, "requestContext must not be null");
        if (requestedTopK <= 0) {
            return new Resolution(0L, 0L, List.of(), List.of());
        }

        var principal = authenticationPort.authenticate(requestContext);
        ActiveCatalogView view = activeViewProvider.get();
        if (view == null || view.catalogVersion() <= 0) {
            return new Resolution(0L, 0L, List.of(), List.of());
        }

        CapabilityVisibility visibility = authorizationPort.resolveVisibility(principal);
        if (visibility == null || !visibility.healthy() || visibility.policyEpoch() <= 0) {
            return new Resolution(view.catalogVersion(), 0L, List.of(), List.of());
        }
        List<CapabilityManifest> visible = view.visibleCapabilities(visibility);
        if (visible == null || visible.isEmpty()) {
            return new Resolution(view.catalogVersion(), visibility.policyEpoch(),
                    List.of(), List.of());
        }

        int limit = Math.min(requestedTopK, maxCandidates);
        // 收窄检索域（WRITE_HIGH 排除 + 本平面的 schema 体积预算）、归一化、BM25 召回与
        // 结果落域校验全部由共用内核执行；本用例不再自持一套检索链路。
        AuthorizedCandidateRetrieval.Retrieved retrieved =
                candidateRetrieval.retrieveWithinAgentScope(query, view, visible,
                        manifest -> schemaSizeBytes(manifest.spec().inputSchema())
                                <= schemaBudgetBytes,
                        Math.max(DEFAULT_RECALL_CANDIDATES, limit));
        if (retrieved.candidates().isEmpty()) {
            return new Resolution(view.catalogVersion(), visibility.policyEpoch(),
                    List.of(), List.of());
        }

        // 与 Host 侧 capability resolve 共用同一份重排规则，使两个入口的 Top-1 必然一致。
        List<AgentCandidateRanker.Ranked> ranked = candidateRanker.rank(
                retrieved.normalizedText(), view, retrieved.candidates());

        Set<String> aliases = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        List<Binding> bindings = new ArrayList<>();
        for (AgentCandidateRanker.Ranked rankedCandidate : ranked) {
            if (candidates.size() >= limit) {
                break;
            }
            CapabilityManifest manifest = rankedCandidate.capability();
            String alias = uniqueAlias(view.catalogVersion(), manifest, aliases);
            aliases.add(alias);
            candidates.add(new Candidate(
                    alias,
                    manifest.spec().displayName(),
                    manifest.spec().description(),
                    new LinkedHashMap<>(manifest.spec().inputSchema()),
                    executionMode(manifest.spec().risk()),
                    rankedCandidate.retrievalScore()));
            bindings.add(new Binding(alias, manifest.metadata().id(),
                    manifest.metadata().version(), view.catalogVersion()));
        }
        return new Resolution(view.catalogVersion(), visibility.policyEpoch(),
                List.copyOf(candidates),
                List.copyOf(bindings));
    }

    private String uniqueAlias(long snapshotVersion,
                               CapabilityManifest manifest,
                               Set<String> aliases) {
        String base = aliasGenerator.generate(snapshotVersion,
                manifest.metadata().id(), manifest.metadata().version());
        if (!aliases.contains(base)) {
            return base;
        }
        int suffix = 2;
        String candidate;
        do {
            candidate = base + "_" + suffix++;
        } while (aliases.contains(candidate));
        return candidate;
    }

    private static String executionMode(RiskLevel risk) {
        return risk == RiskLevel.READ_ONLY ? "DIRECT" : "CONFIRMATION_REQUIRED";
    }

    /** Estimates the UTF-8 JSON size conservatively without adding a mapper dependency. */
    private static int schemaSizeBytes(Map<String, Object> schema) {
        long size = jsonSize(schema);
        return size >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    private static long jsonSize(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof Map<?, ?> map) {
            long size = 2;
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (index++ > 0) {
                    size++;
                }
                size += jsonSize(String.valueOf(entry.getKey()));
                size++;
                size += jsonSize(entry.getValue());
            }
            return size;
        }
        if (value instanceof Iterable<?> iterable) {
            long size = 2;
            int index = 0;
            for (Object item : iterable) {
                if (index++ > 0) {
                    size++;
                }
                size += jsonSize(item);
            }
            return size;
        }
        if (value instanceof String string) {
            long size = 2;
            for (int i = 0; i < string.length(); i++) {
                char character = string.charAt(i);
                size += character == '"' || character == '\\' || character < 0x20
                        ? 2 : String.valueOf(character).getBytes(StandardCharsets.UTF_8).length;
            }
            return size;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record Resolution(long snapshotVersion,
                             long policyEpoch,
                             List<Candidate> candidates,
                             List<Binding> bindings) {
        public Resolution {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
        }

        public Resolution(long snapshotVersion,
                          List<Candidate> candidates,
                          List<Binding> bindings) {
            this(snapshotVersion, 0L, candidates, bindings);
        }
    }

    /** Public fields safe to add to the current model context. */
    public record Candidate(String toolName,
                            String displayName,
                            String description,
                            Map<String, Object> inputSchema,
                            String executionMode,
                            double score) {
        public Candidate {
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }
    }

    /** Host-only routing binding; never serialize this into the model prompt. */
    public record Binding(String toolName,
                          String capabilityId,
                          String capabilityVersion,
                          long snapshotVersion) {
    }
}
