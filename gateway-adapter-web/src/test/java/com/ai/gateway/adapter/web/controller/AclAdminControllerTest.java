package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.console.AclManageUseCase;
import com.ai.gateway.domain.model.AclPolicyStatus;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AclAdminControllerTest {

    @Test
    void policyOverviewReportsRuntimeAclStatus() {
        AclManageUseCase useCase = mock(AclManageUseCase.class);
        AuthorizationPort authorizationPort = mock(AuthorizationPort.class);
        when(useCase.listAclEntries()).thenReturn(List.of());
        when(useCase.listRoles()).thenReturn(List.of());
        when(useCase.listPermissions()).thenReturn(List.of());
        when(authorizationPort.aclPolicyStatus())
                .thenReturn(new AclPolicyStatus(false, 3, "DENY"));
        AclAdminController controller = new AclAdminController(
                useCase, authorizationPort, mock(AuthenticationPort.class));

        Map<String, Object> body = controller.getAclPolicy().getBody();

        assertThat(body).containsEntry("aclLoadHealthy", false)
                .containsEntry("aclEntryCount", 3)
                .containsEntry("emptyAclDecision", "DENY")
                .containsKeys("aclEntries", "roles", "permissions");
    }

    @Test
    void capabilityAclUsesOneCanonicalRouteFamily() {
        List<String> routes = Arrays.stream(AclAdminController.class.getDeclaredMethods())
                .flatMap(method -> mappings(method).stream())
                .filter(path -> path.startsWith("/acl/"))
                .toList();

        assertThat(routes).contains(
                "/acl/capabilities",
                "/acl/capabilities/{capabilityId}/{version}",
                "/acl/policy");
        assertThat(routes).noneMatch(path -> path.startsWith("/acl/entries"));
    }

    private static List<String> mappings(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) return List.of(get.value());
        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) return List.of(put.value());
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        return delete == null ? List.of() : List.of(delete.value());
    }
}
