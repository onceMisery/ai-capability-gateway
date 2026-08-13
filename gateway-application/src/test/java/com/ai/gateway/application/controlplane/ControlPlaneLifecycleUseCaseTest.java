package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.SnapshotNotifier;
import com.ai.gateway.domain.service.LifecycleStateMachine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlPlaneLifecycleUseCaseTest {

    @Test
    void approvalRejectsManifestWhosePersistedStateIsNotValidated() {
        ManifestRepository repository = mock(ManifestRepository.class);
        CapabilityManifest manifest = manifest("order.create", "1.0.0");
        when(repository.findByIdAndVersion("order.create", "1.0.0"))
                .thenReturn(Optional.of(manifest));
        ManifestRepository.ManifestDetail draftDetail =
                detail(manifest, CapabilityLifecycle.DRAFT);
        when(repository.findAllWithDetails()).thenReturn(List.of(draftDetail));
        ManifestApprovalUseCase useCase = new ManifestApprovalUseCase(
                repository, new LifecycleStateMachine());

        ManifestApprovalUseCase.ApprovalResult result =
                useCase.approve("order.create", "1.0.0", "admin");

        assertThat(result.success()).isFalse();
        verify(repository, never()).updateLifecycle(
                "order.create", "1.0.0", CapabilityLifecycle.APPROVED);
    }

    @Test
    void publishIncludesOnlyPersistedApprovedManifests() {
        ManifestRepository repository = mock(ManifestRepository.class);
        CatalogPort catalog = mock(CatalogPort.class);
        SnapshotNotifier notifier = mock(SnapshotNotifier.class);
        CapabilityManifest approved = manifest("order.query", "1.0.0");
        CapabilityManifest draft = manifest("order.create", "1.0.0");
        when(repository.findAll()).thenReturn(List.of(approved, draft));
        ManifestRepository.ManifestDetail approvedDetail =
                detail(approved, CapabilityLifecycle.APPROVED);
        ManifestRepository.ManifestDetail draftDetail =
                detail(draft, CapabilityLifecycle.DRAFT);
        when(repository.findAllWithDetails()).thenReturn(List.of(approvedDetail, draftDetail));
        when(catalog.loadCurrentSnapshot("production"))
                .thenReturn(new CatalogSnapshot(3L, "production", List.of(), "policy", "digest"));
        when(catalog.reserveSnapshotVersion()).thenReturn(41L);
        CatalogPublishUseCase useCase = new CatalogPublishUseCase(repository, catalog, notifier);

        CatalogPublishUseCase.PublishResult result = useCase.publish("production");

        assertThat(result.success()).isTrue();
        ArgumentCaptor<CatalogSnapshot> snapshot = ArgumentCaptor.forClass(CatalogSnapshot.class);
        verify(catalog).saveSnapshot(snapshot.capture());
        assertThat(snapshot.getValue().snapshotVersion()).isEqualTo(41L);
        assertThat(snapshot.getValue().capabilities()).containsExactly(approved);
        verify(repository).updateLifecycle("order.query", "1.0.0", CapabilityLifecycle.PUBLISHED);
        verify(repository, never()).updateLifecycle(
                "order.create", "1.0.0", CapabilityLifecycle.PUBLISHED);
    }

    @Test
    void rollbackPersistsCopiedHistoricalSnapshotBeforeNotification() {
        CatalogPort catalog = mock(CatalogPort.class);
        SnapshotNotifier notifier = mock(SnapshotNotifier.class);
        CapabilityManifest manifest = manifest("order.query", "1.0.0");
        when(catalog.loadSnapshot(2L)).thenReturn(
                new CatalogSnapshot(2L, "production", List.of(manifest), "policy-2", "old"));
        when(catalog.loadCurrentSnapshot("production")).thenReturn(
                new CatalogSnapshot(5L, "production", List.of(), "policy-5", "current"));
        when(catalog.reserveSnapshotVersion()).thenReturn(73L);
        CatalogRollbackUseCase useCase = new CatalogRollbackUseCase(catalog, notifier);

        CatalogRollbackUseCase.RollbackResult result = useCase.rollback(2L, "production");

        assertThat(result.success()).isTrue();
        ArgumentCaptor<CatalogSnapshot> snapshot = ArgumentCaptor.forClass(CatalogSnapshot.class);
        verify(catalog).saveSnapshot(snapshot.capture());
        assertThat(snapshot.getValue().snapshotVersion()).isEqualTo(73L);
        assertThat(snapshot.getValue().capabilities()).containsExactly(manifest);
    }

    @Test
    void suspensionPersistsSnapshotWithoutSuspendedCapability() {
        ManifestRepository repository = mock(ManifestRepository.class);
        CatalogPort catalog = mock(CatalogPort.class);
        SnapshotNotifier notifier = mock(SnapshotNotifier.class);
        CapabilityManifest suspended = manifest("order.create", "1.0.0");
        CapabilityManifest remaining = manifest("order.query", "1.0.0");
        when(catalog.loadCurrentSnapshot("production")).thenReturn(
                new CatalogSnapshot(8L, "production", List.of(suspended, remaining),
                        "policy-8", "current"));
        when(catalog.reserveSnapshotVersion()).thenReturn(97L);
        CapabilitySuspendUseCase useCase = new CapabilitySuspendUseCase(
                repository, catalog, notifier, "production");

        CapabilitySuspendUseCase.SuspendResult result =
                useCase.suspend("order.create", "incident", "admin");

        assertThat(result.success()).isTrue();
        ArgumentCaptor<CatalogSnapshot> snapshot = ArgumentCaptor.forClass(CatalogSnapshot.class);
        verify(catalog).saveSnapshot(snapshot.capture());
        assertThat(snapshot.getValue().snapshotVersion()).isEqualTo(97L);
        assertThat(snapshot.getValue().capabilities()).containsExactly(remaining);
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
