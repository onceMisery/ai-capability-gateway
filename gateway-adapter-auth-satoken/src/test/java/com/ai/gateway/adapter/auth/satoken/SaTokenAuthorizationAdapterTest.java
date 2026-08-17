package com.ai.gateway.adapter.auth.satoken;

import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.CapabilityAclEntry;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AclRepository;
import com.ai.gateway.domain.model.AclPolicyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SaTokenAuthorizationAdapter}.
 *
 * <p>Verifies capability-level ACL decisions, the initial-release
 * degradation rule (empty ACL allows all), wildcard permission handling,
 * and administrative-action gating.</p>
 */
class SaTokenAuthorizationAdapterTest {

    private Principal principal(String subject, List<String> roles, List<String> permissions) {
        return new Principal(subject, 1L, roles, permissions, Instant.now(), "SA_TOKEN_JWT");
    }

    @Test
    @DisplayName("empty ACL allows all authenticated callers (initial release)")
    void emptyAclAllowsAll() {
        SaTokenAuthorizationAdapter adapter = new SaTokenAuthorizationAdapter(true);
        Principal user = principal("u1", List.of("user"), List.of());

        assertThat(adapter.authorizeExecution(user, "order.detail.query", "1.0.0")).isTrue();
    }

    @Test
    @DisplayName("strict mode with empty ACL denies all")
    void strictEmptyAclDeniesAll() {
        SaTokenAuthorizationAdapter adapter = new SaTokenAuthorizationAdapter(false);
        Principal user = principal("u1", List.of("user"), List.of());

        assertThat(adapter.authorizeExecution(user, "order.detail.query", "1.0.0")).isFalse();
    }

    @Test
    @DisplayName("ACL entry grants only matching roles")
    void aclGrantsMatchingRolesOnly() {
        SaTokenAuthorizationAdapter adapter = new SaTokenAuthorizationAdapter();
        adapter.grant("order.detail.query", Set.of("analyst"));

        Principal analyst = principal("a1", List.of("analyst"), List.of());
        Principal plainUser = principal("u1", List.of("user"), List.of());

        assertThat(adapter.authorizeExecution(analyst, "order.detail.query", "1.0.0")).isTrue();
        assertThat(adapter.authorizeExecution(plainUser, "order.detail.query", "1.0.0")).isFalse();
    }

    @Test
    @DisplayName("wildcard permission bypasses ACL")
    void wildcardPermissionBypassesAcl() {
        SaTokenAuthorizationAdapter adapter = new SaTokenAuthorizationAdapter(false);
        adapter.grant("order.detail.query", Set.of("analyst"));

        Principal superUser = principal("s1", List.of("user"), List.of("*"));

        assertThat(adapter.authorizeExecution(superUser, "order.detail.query", "1.0.0")).isTrue();
    }

    @Test
    @DisplayName("authorizeAdmin requires admin role or wildcard")
    void authorizeAdminGating() {
        SaTokenAuthorizationAdapter adapter = new SaTokenAuthorizationAdapter();

        Principal admin = principal("admin1", List.of("admin"), List.of());
        Principal user = principal("u1", List.of("user"), List.of());
        Principal wildcard = principal("w1", List.of("user"), List.of("*"));

        assertThat(adapter.authorizeAdmin(admin, AdminAction.PUBLISH)).isTrue();
        assertThat(adapter.authorizeAdmin(wildcard, AdminAction.IMPORT)).isTrue();
        assertThat(adapter.authorizeAdmin(user, AdminAction.PUBLISH)).isFalse();
    }

    @Test
    @DisplayName("无参构造默认拒绝空 ACL")
    void defaultConstructorDeniesEmptyAcl() {
        SaTokenAuthorizationAdapter adapter = new SaTokenAuthorizationAdapter();
        Principal user = principal("u1", List.of("user"), List.of());

        assertThat(adapter.authorizeExecution(user, "order.detail.query", "1.0.0")).isFalse();
    }

    @Test
    void aclIsScopedByCapabilityVersion() {
        AclRepository repository = mock(AclRepository.class);
        when(repository.findAllAclEntries()).thenReturn(List.of(
                new CapabilityAclEntry("order.detail.query", "1.0.0",
                        List.of("analyst"), List.of("order:detail:read"), Instant.now(), "admin"),
                new CapabilityAclEntry("order.detail.query", "2.0.0",
                        List.of("auditor"), List.of("order:audit:read"), Instant.now(), "admin")));
        SaTokenAuthorizationAdapter adapter = new SaTokenAuthorizationAdapter(false, repository);
        Principal analyst = principal("a1", List.of("analyst"), List.of("order:detail:read"));

        assertThat(adapter.authorizeExecution(analyst, "order.detail.query", "1.0.0")).isTrue();
        assertThat(adapter.authorizeExecution(analyst, "order.detail.query", "2.0.0")).isFalse();
    }

    @Test
    void aclRequiresEveryDeclaredPermission() {
        AclRepository repository = mock(AclRepository.class);
        when(repository.findAllAclEntries()).thenReturn(List.of(
                new CapabilityAclEntry("order.detail.query", "1.0.0",
                        List.of("analyst"), List.of("order:detail:read", "customer:pii:read"),
                        Instant.now(), "admin")));
        SaTokenAuthorizationAdapter adapter = new SaTokenAuthorizationAdapter(false, repository);

        assertThat(adapter.authorizeExecution(
                principal("a1", List.of("analyst"), List.of("order:detail:read")),
                "order.detail.query", "1.0.0")).isFalse();
    }

    @Test
    void aclLoadFailureDeniesWildcardPrincipal() {
        AclRepository repository = mock(AclRepository.class);
        when(repository.findAllAclEntries()).thenThrow(new IllegalStateException("database unavailable"));
        SaTokenAuthorizationAdapter adapter = new SaTokenAuthorizationAdapter(false, repository);

        assertThat(adapter.authorizeExecution(
                principal("admin", List.of("admin"), List.of("*")),
                "order.detail.query", "1.0.0")).isFalse();
    }

    @Test
    void refreshFailureKeepsLastSnapshotButFailsClosedUntilRecovery() {
        AclRepository repository = mock(AclRepository.class);
        CapabilityAclEntry entry = new CapabilityAclEntry("order.detail.query", "1.0.0",
                List.of("analyst"), List.of("order:detail:read"), Instant.now(), "admin");
        when(repository.findAllAclEntries()).thenReturn(List.of(entry))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(List.of());
        SaTokenAuthorizationAdapter adapter = new SaTokenAuthorizationAdapter(false, repository);

        assertThat(adapter.aclPolicyStatus()).isEqualTo(new AclPolicyStatus(true, 1, "DENY"));
        adapter.refreshAcl();
        assertThat(adapter.aclPolicyStatus()).isEqualTo(new AclPolicyStatus(false, 1, "DENY"));
        assertThat(adapter.authorizeExecution(
                principal("a1", List.of("analyst"), List.of("order:detail:read")),
                "order.detail.query", "1.0.0")).isFalse();

        adapter.refreshAcl();
        assertThat(adapter.aclPolicyStatus()).isEqualTo(new AclPolicyStatus(true, 0, "DENY"));
    }

    @Test
    void productionConfigurationDeniesWhenAclIsEmpty() {
        AclRepository repository = mock(AclRepository.class);
        when(repository.findAllAclEntries()).thenReturn(List.of());
        SaTokenAuthConfiguration configuration = new SaTokenAuthConfiguration();

        assertThat(configuration.authorizationPort(repository).authorizeExecution(
                principal("u1", List.of("user"), List.of()),
                "order.detail.query", "1.0.0")).isFalse();
    }
}
