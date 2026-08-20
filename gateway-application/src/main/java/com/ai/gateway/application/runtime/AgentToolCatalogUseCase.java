package com.ai.gateway.application.runtime;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.service.AliasGenerator;
import com.ai.gateway.domain.service.TextNormalizer;

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
 */
public final class AgentToolCatalogUseCase {

    private static final int DEFAULT_MAX_CANDIDATES = 5;
    private static final int DEFAULT_RECALL_CANDIDATES = 20;
    private static final int DEFAULT_SCHEMA_BUDGET_BYTES = 16 * 1024;

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final Supplier<ActiveCatalogView> activeViewProvider;
    private final CandidateRetriever candidateRetriever;
    private final TextNormalizer textNormalizer;
    private final AliasGenerator aliasGenerator;
    private final int maxCandidates;
    private final int schemaBudgetBytes;

    public AgentToolCatalogUseCase(AuthenticationPort authenticationPort,
                                   AuthorizationPort authorizationPort,
                                   InMemoryCatalogManager catalogManager,
                                   CandidateRetriever candidateRetriever,
                                   TextNormalizer textNormalizer,
                                   AliasGenerator aliasGenerator,
                                   String environment) {
        this(authenticationPort, authorizationPort, catalogManager::getActiveView, candidateRetriever,
                textNormalizer, aliasGenerator, environment,
                DEFAULT_MAX_CANDIDATES, DEFAULT_SCHEMA_BUDGET_BYTES);
    }

    /** Compatibility constructor; production wiring must use the in-memory manager. */
    @Deprecated
    public AgentToolCatalogUseCase(AuthenticationPort authenticationPort,
                                   AuthorizationPort authorizationPort,
                                   CatalogPort catalogPort,
                                   CandidateRetriever candidateRetriever,
                                   TextNormalizer textNormalizer,
                                   AliasGenerator aliasGenerator,
                                   String environment) {
        this(authenticationPort, authorizationPort, catalogPort, candidateRetriever,
                textNormalizer, aliasGenerator, environment,
                DEFAULT_MAX_CANDIDATES, DEFAULT_SCHEMA_BUDGET_BYTES);
    }

    /** Compatibility constructor; production wiring must use the in-memory manager. */
    @Deprecated
    public AgentToolCatalogUseCase(AuthenticationPort authenticationPort,
                                   AuthorizationPort authorizationPort,
                                   CatalogPort catalogPort,
                                   CandidateRetriever candidateRetriever,
                                   TextNormalizer textNormalizer,
                                   AliasGenerator aliasGenerator,
                                   String environment,
                                   int maxCandidates,
                                   int schemaBudgetBytes) {
        this(authenticationPort, authorizationPort,
                () -> {
                    var snapshot = catalogPort.loadCurrentSnapshot(environment);
                    return snapshot == null ? null : ActiveCatalogView.from(snapshot);
                },
                candidateRetriever, textNormalizer, aliasGenerator, environment,
                maxCandidates, schemaBudgetBytes);
    }

    AgentToolCatalogUseCase(AuthenticationPort authenticationPort,
                            AuthorizationPort authorizationPort,
                            Supplier<ActiveCatalogView> activeViewProvider,
                            CandidateRetriever candidateRetriever,
                            TextNormalizer textNormalizer,
                            AliasGenerator aliasGenerator,
                            String environment,
                            int maxCandidates,
                            int schemaBudgetBytes) {
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
        this.activeViewProvider = Objects.requireNonNull(activeViewProvider);
        this.candidateRetriever = Objects.requireNonNull(candidateRetriever);
        this.textNormalizer = Objects.requireNonNull(textNormalizer);
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

        List<CapabilityManifest> searchable = visible.stream()
                .filter(Objects::nonNull)
                .filter(manifest -> manifest.spec().risk() != RiskLevel.WRITE_HIGH)
                .filter(manifest -> schemaSizeBytes(manifest.spec().inputSchema()) <= schemaBudgetBytes)
                .toList();

        String normalizedQuery = textNormalizer.normalize(query);
        if (normalizedQuery.isEmpty() || searchable.isEmpty()) {
            return new Resolution(view.catalogVersion(), visibility.policyEpoch(),
                    List.of(), List.of());
        }

        int limit = Math.min(requestedTopK, maxCandidates);
        List<CandidateRetriever.ScoredCapability> retrieved = candidateRetriever.retrieve(
                normalizedQuery, searchable, Math.max(DEFAULT_RECALL_CANDIDATES, limit));
        if (retrieved == null || retrieved.isEmpty()) {
            return new Resolution(view.catalogVersion(), visibility.policyEpoch(),
                    List.of(), List.of());
        }

        retrieved = retrieved.stream()
                .filter(Objects::nonNull)
                .filter(scored -> scored.capability() != null)
                .sorted(java.util.Comparator
                        .comparingDouble((CandidateRetriever.ScoredCapability scored) ->
                                rerankScore(normalizedQuery, scored)).reversed()
                        .thenComparing(scored -> scored.capability().metadata().id())
                        .thenComparing(scored -> scored.capability().metadata().version()))
                .toList();

        Set<String> searchableKeys = searchable.stream()
                .map(this::keyOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> aliases = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        List<Binding> bindings = new ArrayList<>();
        for (CandidateRetriever.ScoredCapability scored : retrieved) {
            if (candidates.size() >= limit || scored == null || scored.capability() == null) {
                break;
            }
            CapabilityManifest manifest = scored.capability();
            if (!searchableKeys.contains(keyOf(manifest))) {
                continue;
            }
            String alias = uniqueAlias(view.catalogVersion(), manifest, aliases);
            aliases.add(alias);
            candidates.add(new Candidate(
                    alias,
                    manifest.spec().displayName(),
                    manifest.spec().description(),
                    new LinkedHashMap<>(manifest.spec().inputSchema()),
                    executionMode(manifest.spec().risk()),
                    scored.score()));
            bindings.add(new Binding(alias, manifest.metadata().id(),
                    manifest.metadata().version(), view.catalogVersion()));
        }
        return new Resolution(view.catalogVersion(), visibility.policyEpoch(),
                List.copyOf(candidates),
                List.copyOf(bindings));
    }

    private double rerankScore(
            String normalizedQuery, CandidateRetriever.ScoredCapability scored) {
        CapabilityManifest manifest = scored.capability();
        String displayName = textNormalizer.normalize(manifest.spec().displayName());
        double score = scored.score();
        if (!displayName.isEmpty() && normalizedQuery.equals(displayName)) {
            score += 10.0;
        } else if (!displayName.isEmpty() && normalizedQuery.contains(displayName)) {
            score += 3.0;
        }
        if (manifest.spec().risk() == RiskLevel.READ_ONLY) {
            score += 0.05;
        }
        return score;
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

    private String keyOf(CapabilityManifest manifest) {
        return manifest.metadata().id() + "\n" + manifest.metadata().version();
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
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
