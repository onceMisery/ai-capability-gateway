package com.ai.gateway.application.console;

import com.ai.gateway.domain.model.CapabilityAclEntry;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.port.AclRepository;
import com.ai.gateway.domain.port.ManifestRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AclManageUseCaseTest {

    @Test
    void saveAclUsesPermissionsDeclaredByManifest() {
        AclRepository aclRepository = mock(AclRepository.class);
        ManifestRepository manifestRepository = mock(ManifestRepository.class);
        CapabilityManifest manifest = manifestWithPermissions("order:detail:read");
        when(manifestRepository.findByIdAndVersion("order.detail.query", "1.0.0"))
                .thenReturn(Optional.of(manifest));
        AclManageUseCase useCase = new AclManageUseCase(aclRepository, manifestRepository);

        useCase.saveAclEntry("order.detail.query", "1.0.0", List.of("analyst"), "admin");

        verify(aclRepository).saveAclEntry(argThat(entry ->
                entry.allowedRoles().equals(List.of("analyst"))
                        && entry.requiredPermissions().equals(List.of("order:detail:read"))));
    }

    @Test
    void saveAclRejectsUnknownCapabilityVersion() {
        AclRepository aclRepository = mock(AclRepository.class);
        ManifestRepository manifestRepository = mock(ManifestRepository.class);
        when(manifestRepository.findByIdAndVersion("missing.capability", "1.0.0"))
                .thenReturn(Optional.empty());
        AclManageUseCase useCase = new AclManageUseCase(aclRepository, manifestRepository);

        assertThatThrownBy(() -> useCase.saveAclEntry(
                "missing.capability", "1.0.0", List.of("analyst"), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing.capability")
                .hasMessageContaining("1.0.0");
    }

    @Test
    void saveAclRejectsEmptyAllowedRoles() {
        AclRepository aclRepository = mock(AclRepository.class);
        ManifestRepository manifestRepository = mock(ManifestRepository.class);
        CapabilityManifest manifest = manifestWithPermissions("order:detail:read");
        when(manifestRepository.findByIdAndVersion("order.detail.query", "1.0.0"))
                .thenReturn(Optional.of(manifest));
        AclManageUseCase useCase = new AclManageUseCase(aclRepository, manifestRepository);

        assertThatThrownBy(() -> useCase.saveAclEntry(
                "order.detail.query", "1.0.0", List.of(), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedRoles");
    }

    @Test
    void savePermissionRejectsWildcardAndMalformedNames() {
        AclManageUseCase useCase = new AclManageUseCase(
                mock(AclRepository.class), mock(ManifestRepository.class));

        assertThatThrownBy(() -> useCase.savePermission("*", "all"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Permission");
        assertThatThrownBy(() -> useCase.savePermission("order:read", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Permission");
    }

    @Test
    void saveRoleRejectsWildcardPermission() {
        AclManageUseCase useCase = new AclManageUseCase(
                mock(AclRepository.class), mock(ManifestRepository.class));

        assertThatThrownBy(() -> useCase.saveRole("admin", "Administrator", List.of("*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
    }

    private static CapabilityManifest manifestWithPermissions(String... permissions) {
        CapabilityManifest.Spec spec = mock(CapabilityManifest.Spec.class);
        when(spec.authorization()).thenReturn(new CapabilityManifest.Authorization(
                List.of(permissions), Map.of()));
        CapabilityManifest manifest = mock(CapabilityManifest.class);
        when(manifest.spec()).thenReturn(spec);
        return manifest;
    }
}
