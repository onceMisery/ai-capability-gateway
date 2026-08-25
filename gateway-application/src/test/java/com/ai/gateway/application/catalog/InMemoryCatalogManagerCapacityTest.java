package com.ai.gateway.application.catalog;

import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.CatalogSnapshotDigest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class InMemoryCatalogManagerCapacityTest {

    @Test
    void coalescesNotificationBurstIntoOneRefreshWorker() {
        CatalogPort catalog = mock(CatalogPort.class);
        Executor refreshExecutor = mock(Executor.class);
        InMemoryCatalogManager manager = new InMemoryCatalogManager(
                catalog, new CapabilityPublicProjectionService(), null, null,
                10, Long.MAX_VALUE, refreshExecutor);

        assertThat(manager.requestRefresh("production")).isTrue();
        assertThat(manager.requestRefresh("production")).isFalse();

        verify(refreshExecutor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        verifyNoMoreInteractions(refreshExecutor);
    }

    @Test
    void rejectsCatalogAndIndexCapacityBeforePublication() {
        CatalogPort catalog = mock(CatalogPort.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        CatalogSnapshot tooMany = snapshot(1L,
                ActiveCatalogViewTest.manifest("orders.first"),
                ActiveCatalogViewTest.manifest("orders.second"));
        when(catalog.loadCurrentSnapshot("production")).thenReturn(tooMany);
        InMemoryCatalogManager capabilityLimited = new InMemoryCatalogManager(
                catalog, new CapabilityPublicProjectionService(),
                new LuceneCandidateRetriever(), telemetry, 1, Long.MAX_VALUE);

        assertThat(capabilityLimited.loadAndActivate("production")).isFalse();
        assertThat(capabilityLimited.getCurrentSnapshotVersion()).isZero();

        CatalogSnapshot oneCapability = snapshot(2L,
                ActiveCatalogViewTest.manifest("orders.index"));
        when(catalog.loadCurrentSnapshot("production")).thenReturn(oneCapability);
        InMemoryCatalogManager indexLimited = new InMemoryCatalogManager(
                catalog, new CapabilityPublicProjectionService(),
                new LuceneCandidateRetriever(), telemetry, 10, 1L);

        assertThat(indexLimited.loadAndActivate("production")).isFalse();
        assertThat(indexLimited.getCurrentSnapshotVersion()).isZero();
    }

    @Test
    void rejectsBuildAfterTimeoutWithoutPublishingSnapshot() throws Exception {
        CatalogPort catalog = mock(CatalogPort.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        CatalogSnapshot snapshot = snapshot(4L,
                ActiveCatalogViewTest.manifest("orders.timeout"));
        when(catalog.loadCurrentSnapshot("production")).thenAnswer(invocation -> {
            Thread.sleep(20L);
            return snapshot;
        });

        InMemoryCatalogManager manager = new InMemoryCatalogManager(
                catalog, new CapabilityPublicProjectionService(), null, telemetry,
                10, Long.MAX_VALUE, Long.MAX_VALUE, 1L, 0L, Runnable::run);

        assertThat(manager.loadAndActivate("production")).isFalse();
        assertThat(manager.getCurrentSnapshotVersion()).isZero();
        verify(telemetry).increment("gateway.catalog.refresh",
                Map.of("outcome", "build_timeout"));
    }

    @Test
    void rejectsCatalogActivationWhenIndexWarmupFails() {
        CatalogPort catalog = mock(CatalogPort.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        CatalogSnapshot snapshot = snapshot(5L,
                ActiveCatalogViewTest.manifest("orders.warmup"));
        when(catalog.loadCurrentSnapshot("production")).thenReturn(snapshot);
        LuceneCandidateRetriever retriever = spy(new LuceneCandidateRetriever());
        doReturn(List.of()).when(retriever).retrieve(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(ActiveCatalogView.class),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(1));
        InMemoryCatalogManager manager = new InMemoryCatalogManager(
                catalog, new CapabilityPublicProjectionService(), retriever,
                telemetry, 10, Long.MAX_VALUE);

        assertThat(manager.loadAndActivate("production")).isFalse();
        assertThat(manager.getCurrentSnapshotVersion()).isZero();
        assertThat(retriever.getIndexedSnapshotVersion()).isEqualTo(-1L);
        verify(telemetry).increment("gateway.catalog.refresh",
                Map.of("outcome", "index_warmup_failed"));
    }

    @Test
    void rejectsThirdGenerationUntilRetiredViewLeaseDrains() {
        CatalogPort catalog = mock(CatalogPort.class);
        CatalogSnapshot first = snapshot(1L,
                ActiveCatalogViewTest.manifest("orders.first"));
        CatalogSnapshot second = snapshot(2L,
                ActiveCatalogViewTest.manifest("orders.second"));
        CatalogSnapshot third = snapshot(3L,
                ActiveCatalogViewTest.manifest("orders.third"));
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(first, second, third);
        InMemoryCatalogManager manager = new InMemoryCatalogManager(
                catalog, new CapabilityPublicProjectionService(),
                new LuceneCandidateRetriever(), mock(TelemetryPort.class),
                10, Long.MAX_VALUE);

        assertThat(manager.loadAndActivate("production")).isTrue();
        ActiveCatalogView.ViewLease firstLease = manager.acquireActiveView();
        assertThat(firstLease).isNotNull();
        assertThat(manager.loadAndActivate("production")).isTrue();

        assertThat(manager.loadAndActivate("production")).isFalse();
        assertThat(manager.getCurrentSnapshotVersion()).isEqualTo(2L);

        firstLease.close();
        assertThat(manager.loadAndActivate("production")).isTrue();
        assertThat(manager.getCurrentSnapshotVersion()).isEqualTo(3L);
    }

    @Test
    void ignoresSameOrOlderCatalogVersionsAndKeepsPublishedIndex() {
        CatalogPort catalog = mock(CatalogPort.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        CatalogSnapshot first = snapshot(2L,
                ActiveCatalogViewTest.manifest("orders.first"));
        CatalogSnapshot same = snapshot(2L,
                ActiveCatalogViewTest.manifest("orders.same"));
        CatalogSnapshot older = snapshot(1L,
                ActiveCatalogViewTest.manifest("orders.older"));
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(first, same, older);
        LuceneCandidateRetriever retriever = spy(new LuceneCandidateRetriever());
        InMemoryCatalogManager manager = new InMemoryCatalogManager(
                catalog, new CapabilityPublicProjectionService(), retriever,
                telemetry, 10, Long.MAX_VALUE);

        assertThat(manager.loadAndActivate("production")).isTrue();
        assertThat(retriever.getIndexedSnapshotVersion()).isEqualTo(2L);

        assertThat(manager.loadAndActivate("production")).isFalse();
        assertThat(manager.getCurrentSnapshotVersion()).isEqualTo(2L);
        assertThat(manager.findCapability("orders.first", "1.0.0")).isPresent();
        assertThat(manager.findCapability("orders.same", "1.0.0")).isEmpty();
        assertThat(retriever.getIndexedSnapshotVersion()).isEqualTo(2L);

        assertThat(manager.loadAndActivate("production")).isFalse();
        assertThat(manager.getCurrentSnapshotVersion()).isEqualTo(2L);
        assertThat(retriever.getIndexedSnapshotVersion()).isEqualTo(2L);
        verify(retriever, org.mockito.Mockito.times(1))
                .buildIndex(org.mockito.ArgumentMatchers.any(CatalogSnapshot.class));
        verify(telemetry, org.mockito.Mockito.atLeast(2)).increment(
                "gateway.catalog.refresh", Map.of("outcome", "version_not_newer"));
    }

    @Test
    void retriesLeaseAgainstCurrentViewWhenThePreviouslyReadViewWasRetired()
            throws Exception {
        InMemoryCatalogManager manager = new InMemoryCatalogManager(
                mock(CatalogPort.class));
        AtomicReference<ActiveCatalogView> activeView = activeViewReference(manager);
        ActiveCatalogView firstView = mock(ActiveCatalogView.class);
        ActiveCatalogView currentView = mock(ActiveCatalogView.class);
        ActiveCatalogView.ViewLease currentLease = mock(
                ActiveCatalogView.ViewLease.class);
        activeView.set(firstView);
        when(firstView.acquireLease()).thenAnswer(invocation -> {
            activeView.set(currentView);
            return null;
        });
        when(currentView.acquireLease()).thenReturn(currentLease);

        assertThat(manager.acquireActiveView()).isSameAs(currentLease);
        verify(firstView).acquireLease();
        verify(currentView).acquireLease();
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<ActiveCatalogView> activeViewReference(
            InMemoryCatalogManager manager) throws Exception {
        Field field = InMemoryCatalogManager.class.getDeclaredField("activeView");
        field.setAccessible(true);
        return (AtomicReference<ActiveCatalogView>) field.get(manager);
    }

    private static CatalogSnapshot snapshot(
            long version, CapabilityManifest... capabilities) {
        List<CapabilityManifest> manifests = Arrays.asList(capabilities);
        CatalogSnapshot unsigned = new CatalogSnapshot(
                version, "production", manifests, "policy-v1", "");
        return new CatalogSnapshot(version, "production", manifests, "policy-v1",
                CatalogSnapshotDigest.sha256(unsigned));
    }
}
