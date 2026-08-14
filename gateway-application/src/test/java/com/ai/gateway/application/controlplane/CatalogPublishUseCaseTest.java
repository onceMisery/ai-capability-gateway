package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.SnapshotNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogPublishUseCaseTest {

    @Test
    void publishAllApprovedManifestsWhenNoSelectionProvided() {
        ManifestRepository repository = mock(ManifestRepository.class);
        CatalogPort catalog = mock(CatalogPort.class);
        SnapshotNotifier notifier = mock(SnapshotNotifier.class);
        CapabilityManifest approvedA = manifest("order.query", "1.0.0");
        CapabilityManifest approvedB = manifest("order.create", "1.0.0");
        CapabilityManifest draft = manifest("order.cancel", "1.0.0");
        when(repository.findAllWithDetails()).thenReturn(List.of(
                detail(approvedA, CapabilityLifecycle.APPROVED),
                detail(approvedB, CapabilityLifecycle.APPROVED),
                detail(draft, CapabilityLifecycle.DRAFT)));
        when(catalog.reserveSnapshotVersion()).thenReturn(42L);
        CatalogPublishUseCase useCase = new CatalogPublishUseCase(repository, catalog, notifier);

        CatalogPublishUseCase.PublishResult result = useCase.publish("production");

        assertThat(result.success()).isTrue();
        assertThat(result.snapshotVersion()).isEqualTo(42L);
        ArgumentCaptor<CatalogSnapshot> snapshot = ArgumentCaptor.forClass(CatalogSnapshot.class);
        verify(catalog).saveSnapshot(snapshot.capture());
        assertThat(snapshot.getValue().capabilities())
                .containsExactlyInAnyOrder(approvedA, approvedB);
        verify(repository).updateLifecycle("order.query", "1.0.0", CapabilityLifecycle.PUBLISHED);
        verify(repository).updateLifecycle("order.create", "1.0.0", CapabilityLifecycle.PUBLISHED);
        verify(repository, never()).updateLifecycle(
                "order.cancel", "1.0.0", CapabilityLifecycle.PUBLISHED);
        verify(notifier).notifySnapshotPublished(42L);
    }

    @Test
    void publishOnlySelectedApprovedCapabilities() {
        ManifestRepository repository = mock(ManifestRepository.class);
        CatalogPort catalog = mock(CatalogPort.class);
        SnapshotNotifier notifier = mock(SnapshotNotifier.class);
        CapabilityManifest selected = manifest("order.query", "1.0.0");
        CapabilityManifest notSelected = manifest("order.create", "1.0.0");
        CapabilityManifest wrongVersion = manifest("order.query", "0.9.0");
        when(repository.findAllWithDetails()).thenReturn(List.of(
                detail(selected, CapabilityLifecycle.APPROVED),
                detail(notSelected, CapabilityLifecycle.APPROVED),
                detail(wrongVersion, CapabilityLifecycle.APPROVED)));
        when(catalog.reserveSnapshotVersion()).thenReturn(43L);
        CatalogPublishUseCase useCase = new CatalogPublishUseCase(repository, catalog, notifier);

        CatalogPublishUseCase.PublishResult result = useCase.publish("production", List.of(
                new CatalogPublishUseCase.SelectedCapability("order.query", "1.0.0")));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<CatalogSnapshot> snapshot = ArgumentCaptor.forClass(CatalogSnapshot.class);
        verify(catalog).saveSnapshot(snapshot.capture());
        assertThat(snapshot.getValue().capabilities()).containsExactly(selected);
        verify(repository).updateLifecycle("order.query", "1.0.0", CapabilityLifecycle.PUBLISHED);
        verify(repository, never()).updateLifecycle(
                "order.create", "1.0.0", CapabilityLifecycle.PUBLISHED);
        verify(repository, never()).updateLifecycle(
                "order.query", "0.9.0", CapabilityLifecycle.PUBLISHED);
    }

    @Test
    void publishSelectedIgnoresNonApprovedCapabilities() {
        ManifestRepository repository = mock(ManifestRepository.class);
        CatalogPort catalog = mock(CatalogPort.class);
        SnapshotNotifier notifier = mock(SnapshotNotifier.class);
        CapabilityManifest draft = manifest("order.query", "1.0.0");
        when(repository.findAllWithDetails()).thenReturn(List.of(
                detail(draft, CapabilityLifecycle.DRAFT)));
        CatalogPublishUseCase useCase = new CatalogPublishUseCase(repository, catalog, notifier);

        CatalogPublishUseCase.PublishResult result = useCase.publish("production", List.of(
                new CatalogPublishUseCase.SelectedCapability("order.query", "1.0.0")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("No approved manifests to publish");
        verify(catalog, never()).saveSnapshot(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).updateLifecycle(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private static ManifestRepository.ManifestDetail detail(
            CapabilityManifest manifest, CapabilityLifecycle lifecycle) {
        return new ManifestRepository.ManifestDetail(
                manifest, lifecycle, "digest-" + manifest.metadata().id(), Instant.now());
    }

    private static CapabilityManifest manifest(String id, String version) {
        CapabilityManifest manifest = mock(CapabilityManifest.class, RETURNS_DEEP_STUBS);
        when(manifest.apiVersion()).thenReturn("gateway.ai/v1");
        when(manifest.kind()).thenReturn("Capability");
        when(manifest.metadata().id()).thenReturn(id);
        when(manifest.metadata().version()).thenReturn(version);
        when(manifest.spec().displayName()).thenReturn(id);
        when(manifest.spec().description()).thenReturn(id);
        when(manifest.spec().risk()).thenReturn(RiskLevel.READ_ONLY);
        when(manifest.spec().invocation().arguments()).thenReturn(List.of());
        when(manifest.spec().invocation().interfaceName()).thenReturn("example.Service");
        when(manifest.spec().invocation().method()).thenReturn("invoke");
        when(manifest.spec().invocation().serialization()).thenReturn("hessian2");
        when(manifest.spec().output().projections()).thenReturn(List.of());
        when(manifest.spec().output().redactions()).thenReturn(List.of());
        when(manifest.spec().authorization()).thenReturn(null);
        return manifest;
    }
}
