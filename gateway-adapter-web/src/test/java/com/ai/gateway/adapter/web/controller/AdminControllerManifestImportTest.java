package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.filter.AdminAuthenticationFilter;
import com.ai.gateway.adapter.web.manifest.ManifestDocumentMapper;
import com.ai.gateway.application.controlplane.CapabilitySuspendUseCase;
import com.ai.gateway.application.controlplane.CapabilityResumeUseCase;
import com.ai.gateway.application.controlplane.CatalogPublishUseCase;
import com.ai.gateway.application.controlplane.CatalogRollbackUseCase;
import com.ai.gateway.application.controlplane.CatalogSnapshotQueryUseCase;
import com.ai.gateway.application.controlplane.ManifestApprovalUseCase;
import com.ai.gateway.application.controlplane.ManifestImportUseCase;
import com.ai.gateway.application.controlplane.ManifestValidationUseCase;
import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.ManifestDocumentValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

class AdminControllerManifestImportTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRejectInvalidDocumentBeforeCallingImportUseCase() throws Exception {
        ManifestImportUseCase importUseCase = mock(ManifestImportUseCase.class);
        AuthenticationPort authenticationPort = mock(AuthenticationPort.class);
        AuthorizationPort authorizationPort = mock(AuthorizationPort.class);
        ManifestDocumentValidator documentValidator = mock(ManifestDocumentValidator.class);
        ManifestDocumentMapper documentMapper = mock(ManifestDocumentMapper.class);

        prepareAuthenticatedRequest(authenticationPort, authorizationPort);
        when(documentValidator.validate(any())).thenReturn(
                ValidationReport.failure(List.of("metadata: required property missing")));

        AdminController controller = controller(
                importUseCase, authenticationPort, authorizationPort, documentValidator);

        var response = controller.importManifest(
                new ObjectMapper().readTree("{\"apiVersion\":\"gateway.ai/v1\"}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", "REJECTED");
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) response.getBody()
                .get("validationReport");
        assertThat(report.get("errors")).asList()
                .contains("metadata: required property missing");
        verifyNoInteractions(importUseCase);
    }

    @Test
    void shouldRejectMappingFailureBeforeCallingImportUseCase() throws Exception {
        ManifestImportUseCase importUseCase = mock(ManifestImportUseCase.class);
        AuthenticationPort authenticationPort = mock(AuthenticationPort.class);
        AuthorizationPort authorizationPort = mock(AuthorizationPort.class);
        ManifestDocumentValidator documentValidator = mock(ManifestDocumentValidator.class);

        prepareAuthenticatedRequest(authenticationPort, authorizationPort);
        when(documentValidator.validate(any())).thenReturn(ValidationReport.success());

        AdminController controller = controller(
                importUseCase, authenticationPort, authorizationPort, documentValidator);
        var response = controller.importManifest(new ObjectMapper().readTree("""
                {
                  "apiVersion": "gateway.ai/v1",
                  "kind": "Capability",
                  "metadata": {},
                  "spec": {"risk": "INVALID_RISK"}
                }
                """));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", "REJECTED");
        assertThat(response.getBody()).containsEntry("error", "Manifest 字段映射失败");
        verifyNoInteractions(importUseCase);
    }

    @Test
    void shouldReturnStableCodeAndChineseMessageForDuplicateManifest() throws Exception {
        ManifestImportUseCase importUseCase = mock(ManifestImportUseCase.class);
        AuthenticationPort authenticationPort = mock(AuthenticationPort.class);
        AuthorizationPort authorizationPort = mock(AuthorizationPort.class);
        ManifestDocumentValidator documentValidator = mock(ManifestDocumentValidator.class);
        ManifestDocumentMapper documentMapper = mock(ManifestDocumentMapper.class);

        prepareAuthenticatedRequest(authenticationPort, authorizationPort);
        when(documentValidator.validate(any())).thenReturn(ValidationReport.success());
        JsonNode document = new ObjectMapper().readTree("{\"metadata\":{},\"spec\":{}}");
        CapabilityManifest mappedManifest = mock(CapabilityManifest.class, RETURNS_DEEP_STUBS);
        when(documentMapper.toValidationTree(any())).thenReturn(document);
        when(documentMapper.toDomain(any())).thenReturn(mappedManifest);
        when(mappedManifest.metadata().id()).thenReturn("order.detail.query");
        when(mappedManifest.metadata().version()).thenReturn("1.0.0");
        when(importUseCase.importManifest(any())).thenReturn(
                new ManifestImportUseCase.ImportResult(
                        false,
                        ValidationReport.success(),
                        null,
                        "能力「order.detail.query」的版本「1.0.0」已存在，不能重复导入；如需修改内容，请递增版本号。"));

        AdminController controller = controller(
                importUseCase, authenticationPort, authorizationPort, documentValidator, documentMapper);

        var response = controller.importManifest(document);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", "REJECTED");
        assertThat(response.getBody()).containsEntry(
                "errorCode", "MANIFEST_IMPORT_REJECTED");
        assertThat(response.getBody()).containsEntry(
                "error", "能力「order.detail.query」的版本「1.0.0」已存在，不能重复导入；如需修改内容，请递增版本号。");
    }

    private static void prepareAuthenticatedRequest(
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort) {
        Principal principal = new Principal(
                "admin", 1L, List.of("admin"), List.of(), Instant.now(), "TEST");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/admin/v1/manifests:import");
        request.setAttribute(AdminAuthenticationFilter.PRINCIPAL_ATTRIBUTE, principal);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(authorizationPort.authorizeAdmin(principal, AdminAction.IMPORT)).thenReturn(true);
    }

    private static AdminController controller(
            ManifestImportUseCase importUseCase,
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            ManifestDocumentValidator documentValidator) {
        return new AdminController(
                importUseCase,
                mock(ManifestApprovalUseCase.class),
                mock(CatalogPublishUseCase.class),
                mock(CatalogRollbackUseCase.class),
                mock(CapabilitySuspendUseCase.class),
                mock(CapabilityResumeUseCase.class),
                mock(ManifestValidationUseCase.class),
                mock(CatalogSnapshotQueryUseCase.class),
                authenticationPort,
                authorizationPort,
                documentValidator,
                new ManifestDocumentMapper(new ObjectMapper()));
    }

    private static AdminController controller(
            ManifestImportUseCase importUseCase,
            AuthenticationPort authenticationPort,
            AuthorizationPort authorizationPort,
            ManifestDocumentValidator documentValidator,
            ManifestDocumentMapper documentMapper) {
        return new AdminController(
                importUseCase,
                mock(ManifestApprovalUseCase.class),
                mock(CatalogPublishUseCase.class),
                mock(CatalogRollbackUseCase.class),
                mock(CapabilitySuspendUseCase.class),
                mock(CapabilityResumeUseCase.class),
                mock(ManifestValidationUseCase.class),
                mock(CatalogSnapshotQueryUseCase.class),
                authenticationPort,
                authorizationPort,
                documentValidator,
                documentMapper);
    }
}
