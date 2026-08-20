package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.TextNormalizer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCapabilityResolverTest {

    @Test
    void authenticationTimeCountsAgainstResolveDeadline() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CandidateRetriever retriever = mock(CandidateRetriever.class);
        CatalogPort catalog = mock(CatalogPort.class);
        Principal principal = AgentTestFixtures.principal("user-1");
        when(authentication.authenticate(any())).thenAnswer(invocation -> {
            Thread.sleep(200L);
            return principal;
        });

        InMemoryCatalogManager manager = new InMemoryCatalogManager(catalog);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AgentCapabilityResolver resolver = new AgentCapabilityResolver(
                    authentication, authorization, manager, retriever, new TextNormalizer(),
                    new ToolReferenceService("k1", key("current-key"), null, null, 120L),
                    mock(TelemetryPort.class), executor, 20L);

            AgentCapabilityResolver.Resolution result = resolver.resolve(
                    RequestContext.empty(), "Order detail", 5);

            assertThat(result.status()).isEqualTo(AgentCapabilityResolver.Status.ERROR);
            assertThat(result.errorCode()).isEqualTo("RESOLVE_TIMEOUT");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void policySnapshotTimeCountsAgainstResolveDeadline() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CandidateRetriever retriever = mock(CandidateRetriever.class);
        CatalogPort catalog = mock(CatalogPort.class);
        Principal principal = AgentTestFixtures.principal("user-1");
        CapabilityManifest manifest = AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order");
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(AgentTestFixtures.snapshot(8L, manifest));
        InMemoryCatalogManager manager = new InMemoryCatalogManager(catalog);
        assertThat(manager.loadAndActivate("production")).isTrue();
        when(authentication.authenticate(any())).thenReturn(principal);
        when(authorization.resolvePolicySnapshot(principal)).thenAnswer(invocation -> {
            Thread.sleep(200L);
            return PolicySnapshot.from(CapabilityVisibility.all(42L));
        });
        when(retriever.indexedCatalogVersion()).thenReturn(8L);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AgentCapabilityResolver resolver = new AgentCapabilityResolver(
                    authentication, authorization, manager, retriever, new TextNormalizer(),
                    new ToolReferenceService("k1", key("current-key"), null, null, 120L),
                    mock(TelemetryPort.class), executor, 20L);

            AgentCapabilityResolver.Resolution result = resolver.resolve(
                    RequestContext.empty(), "Order detail", 5);

            assertThat(result.status()).isEqualTo(AgentCapabilityResolver.Status.ERROR);
            assertThat(result.errorCode()).isEqualTo("RESOLVE_TIMEOUT");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void saturatedResolveExecutorFailsFastWithStableCapacityError() throws Exception {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CandidateRetriever retriever = mock(CandidateRetriever.class);
        CatalogPort catalog = mock(CatalogPort.class);
        Principal principal = AgentTestFixtures.principal("user-1");
        CapabilityManifest manifest = AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order");
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(AgentTestFixtures.snapshot(8L, manifest));
        InMemoryCatalogManager manager = new InMemoryCatalogManager(catalog);
        assertThat(manager.loadAndActivate("production")).isTrue();
        when(retriever.indexedCatalogVersion()).thenReturn(8L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
        executor.execute(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        try {
            AgentCapabilityResolver resolver = new AgentCapabilityResolver(
                    authentication, authorization, manager,
                    retriever, new TextNormalizer(),
                    new ToolReferenceService("k1", key("current-key"), null, null, 120L),
                    mock(TelemetryPort.class), executor, 100L);

            AgentCapabilityResolver.Resolution result = resolver.resolve(
                    principal, "Order detail", 5);

            assertThat(result.status()).isEqualTo(AgentCapabilityResolver.Status.ERROR);
            assertThat(result.errorCode()).isEqualTo("RESOLVE_CAPACITY_EXCEEDED");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void resolvesFromActiveViewWithoutReloadingDatabaseOrReturningBindings() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CandidateRetriever retriever = mock(CandidateRetriever.class);
        CatalogPort catalog = mock(CatalogPort.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        Principal principal = AgentTestFixtures.principal("user-1");
        CapabilityManifest manifest = AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order");
        CatalogSnapshot snapshot = AgentTestFixtures.snapshot(8L, manifest);
        when(catalog.loadCurrentSnapshot("production")).thenReturn(snapshot);
        InMemoryCatalogManager manager = new InMemoryCatalogManager(catalog);
        assertThat(manager.loadAndActivate("production")).isTrue();

        when(authentication.authenticate(any())).thenReturn(principal);
        when(authorization.resolvePolicySnapshot(principal)).thenReturn(
                PolicySnapshot.from(CapabilityVisibility.all(42L)));
        when(retriever.indexedCatalogVersion()).thenReturn(8L);
        when(retriever.retrieve(anyString(), anyList(), eq(20)))
                .thenReturn(List.of(new CandidateRetriever.ScoredCapability(manifest, 5.0d)));
        ToolReferenceService references = new ToolReferenceService(
                "k1", key("current-key"), null, null, 120L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AgentCapabilityResolver resolver = new AgentCapabilityResolver(
                    authentication, authorization, manager, retriever, new TextNormalizer(),
                    references, telemetry, executor, 1_000L);

            AgentCapabilityResolver.Resolution first = resolver.resolve(
                    RequestContext.empty(), "Order detail", 5);
            AgentCapabilityResolver.Resolution second = resolver.resolve(
                    RequestContext.empty(), "Order detail", 5);

            assertThat(first.status())
                    .withFailMessage("unexpected resolve error: %s", first.errorCode())
                    .isEqualTo(AgentCapabilityResolver.Status.RESOLVED);
            assertThat(first.catalogVersion()).isEqualTo(8L);
            assertThat(first.policyEpoch()).isEqualTo(42L);
            assertThat(first.candidates()).hasSize(1);
            assertThat(first.candidates().get(0).toolRef())
                    .doesNotContain("orders.detail.query")
                    .doesNotContain("1.0.0");
            assertThat(first.candidates().get(0).argumentContract().toString())
                    .doesNotContain("orgId");
            assertThat(second.candidates()).hasSize(1);
            verify(catalog, times(1)).loadCurrentSnapshot("production");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void catalogAndLuceneVersionMismatchFailsClosed() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CandidateRetriever retriever = mock(CandidateRetriever.class);
        CatalogPort catalog = mock(CatalogPort.class);
        Principal principal = AgentTestFixtures.principal("user-1");
        CapabilityManifest manifest = AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order");
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(AgentTestFixtures.snapshot(8L, manifest));
        InMemoryCatalogManager manager = new InMemoryCatalogManager(catalog);
        assertThat(manager.loadAndActivate("production")).isTrue();
        when(authentication.authenticate(any())).thenReturn(principal);
        when(retriever.indexedCatalogVersion()).thenReturn(7L);

        AgentCapabilityResolver resolver = new AgentCapabilityResolver(
                authentication, authorization, manager, retriever, new TextNormalizer(),
                new ToolReferenceService("k1", key("current-key"), null, null, 120L),
                mock(TelemetryPort.class));

        AgentCapabilityResolver.Resolution result = resolver.resolve(
                RequestContext.empty(), "Order detail", 5);

        assertThat(result.status()).isEqualTo(AgentCapabilityResolver.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("CATALOG_INDEX_NOT_READY");
    }

    @Test
    void retrievalTimeoutFailsClosedWithStableResolveTimeout() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        CandidateRetriever retriever = mock(CandidateRetriever.class);
        CatalogPort catalog = mock(CatalogPort.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        Principal principal = AgentTestFixtures.principal("user-1");
        CapabilityManifest manifest = AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order");
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(AgentTestFixtures.snapshot(8L, manifest));
        InMemoryCatalogManager manager = new InMemoryCatalogManager(catalog);
        assertThat(manager.loadAndActivate("production")).isTrue();
        when(authentication.authenticate(any())).thenReturn(principal);
        when(authorization.resolvePolicySnapshot(principal)).thenReturn(
                PolicySnapshot.from(CapabilityVisibility.all(42L)));
        when(retriever.indexedCatalogVersion()).thenReturn(8L);
        when(retriever.retrieve(anyString(), anyList(), eq(20)))
                .thenAnswer(invocation -> {
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of(new CandidateRetriever.ScoredCapability(manifest, 5.0d));
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AgentCapabilityResolver resolver = new AgentCapabilityResolver(
                    authentication, authorization, manager, retriever, new TextNormalizer(),
                    new ToolReferenceService("k1", key("current-key"), null, null, 120L),
                    telemetry, executor, 20L);

            AgentCapabilityResolver.Resolution result = resolver.resolve(
                    RequestContext.empty(), "Order detail", 5);

            assertThat(result.status()).isEqualTo(AgentCapabilityResolver.Status.ERROR);
            assertThat(result.errorCode()).isEqualTo("RESOLVE_TIMEOUT");
        } finally {
            executor.shutdownNow();
        }
    }

    private static byte[] key(String seed) {
        return (seed + "-0123456789abcdef0123456789abcdef")
                .substring(0, 32).getBytes(StandardCharsets.UTF_8);
    }
}
