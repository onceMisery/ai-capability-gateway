package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.AliasGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acceptance tests for the query-less tool projection use case (design §4.3–§4.4).
 *
 * <p>The invariants under test are the ones the MCP direct-projection face rests on:
 * only authorized read-only capabilities are projected, aliases carry no real capability
 * id, trusted fields never reach the model-visible schema, budget truncation degrades
 * presentation but never executability, and every unavailability reason collapses into a
 * single indistinguishable error code.</p>
 *
 * @author cmiracle@163.com
 */
class AgentToolProjectionUseCaseTest {

    private static final AgentToolProjectionUseCase.ProjectionBudget GENEROUS =
            new AgentToolProjectionUseCase.ProjectionBudget(64, 131_072L);

    @Test
    void projectsAuthorizedReadOnlyCapabilityWithoutLeakingIdentifiersOrTrustedFields() {
        Fixture fixture = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));

        AgentToolProjectionUseCase.ProjectionResult result =
                fixture.useCase.project(RequestContext.empty(), GENEROUS);

        assertThat(result.status()).isEqualTo(AgentToolProjectionUseCase.Status.COMPLETED);
        assertThat(result.tools()).hasSize(1);
        assertThat(result.eligibleCount()).isEqualTo(1);
        assertThat(result.degraded()).isFalse();
        AgentToolProjectionUseCase.ProjectedTool tool = result.tools().get(0);
        assertThat(tool.alias()).startsWith("cap_");
        // alias 不得可反推真实能力 id，schema 不得包含 PRINCIPAL 注入字段。
        assertThat(tool.alias()).doesNotContain("orders");
        assertThat(tool.inputSchema().toString()).contains("orderNo").doesNotContain("orgId");
    }

    @Test
    void writeCapabilitiesAreNeverProjectedEvenWhenAuthorized() {
        Fixture fixture = Fixture.with(
                AgentTestFixtures.manifest("orders.detail.query", RiskLevel.READ_ONLY,
                        "Query one order"),
                AgentTestFixtures.manifest("orders.cancel", RiskLevel.WRITE_LOW,
                        "Cancel one order"));

        AgentToolProjectionUseCase.ProjectionResult result =
                fixture.useCase.project(RequestContext.empty(), GENEROUS);

        assertThat(result.tools()).hasSize(1);
        assertThat(result.eligibleCount()).isEqualTo(1);
    }

    @Test
    void bindIssuesToolRefForProjectedAliasAndRecordsAnIndependentTurn() {
        Fixture fixture = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        String alias = fixture.useCase.project(RequestContext.empty(), GENEROUS)
                .tools().get(0).alias();

        AgentToolProjectionUseCase.BindResult bound = fixture.useCase.bind(
                RequestContext.empty(), alias, "turn-1", "request-1");

        assertThat(bound.status()).isEqualTo(AgentToolProjectionUseCase.Status.COMPLETED);
        assertThat(bound.errorCode()).isNull();
        assertThat(bound.alias()).isEqualTo(alias);
        assertThat(bound.toolRef()).isNotBlank();
        assertThat(bound.agentTurnId()).isEqualTo("turn-1");
        assertThat(bound.expiresAt()).isNotNull();
        AgentTurnStore.StoredTurn stored = fixture.turnStore.find(
                com.ai.gateway.domain.service.PrincipalFingerprint.digest(fixture.principal),
                "turn-1").orElseThrow();
        assertThat(stored.state().allowedToolRefs()).containsExactly(bound.toolRef());
        assertThat(stored.state().selectedToolRef()).isNull();
    }

    @Test
    void unknownAliasAndRevokedCapabilityAreIndistinguishable() {
        Fixture fixture = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        String alias = fixture.useCase.project(RequestContext.empty(), GENEROUS)
                .tools().get(0).alias();

        AgentToolProjectionUseCase.BindResult unknown = fixture.useCase.bind(
                RequestContext.empty(), "cap_neverissued", "turn-1", "request-1");
        when(fixture.authorization.authorizeExecution(any(), anyString(), anyString()))
                .thenReturn(false);
        AgentToolProjectionUseCase.BindResult revoked = fixture.useCase.bind(
                RequestContext.empty(), alias, "turn-2", "request-2");

        assertThat(unknown.status()).isEqualTo(AgentToolProjectionUseCase.Status.ERROR);
        assertThat(unknown.errorCode()).isEqualTo("CAPABILITY_UNAVAILABLE");
        assertThat(revoked.errorCode()).isEqualTo(unknown.errorCode());
        assertThat(revoked.toolRef()).isNull();
    }

    @Test
    void truncatedProjectionDegradesPresentationButKeepsTruncatedCapabilityExecutable() {
        Fixture fixture = Fixture.with(
                AgentTestFixtures.manifest("orders.a.query", RiskLevel.READ_ONLY, "A"),
                AgentTestFixtures.manifest("orders.b.query", RiskLevel.READ_ONLY, "B"));
        List<String> allAliases = fixture.useCase.project(RequestContext.empty(), GENEROUS)
                .tools().stream()
                .map(AgentToolProjectionUseCase.ProjectedTool::alias)
                .toList();

        AgentToolProjectionUseCase.ProjectionResult truncated = fixture.useCase.project(
                RequestContext.empty(),
                new AgentToolProjectionUseCase.ProjectionBudget(1, 131_072L));

        assertThat(allAliases).hasSize(2);
        assertThat(truncated.tools()).hasSize(1);
        assertThat(truncated.degraded()).isTrue();
        assertThat(truncated.eligibleCount()).isEqualTo(2);
        // 被裁掉的能力仍然被授权，因此绑定必须依旧成功——裁剪是展示策略而非授权策略。
        String dropped = allAliases.stream()
                .filter(alias -> !alias.equals(truncated.tools().get(0).alias()))
                .findFirst().orElseThrow();
        assertThat(fixture.useCase.bind(RequestContext.empty(), dropped, "turn-1", "req-1")
                .status()).isEqualTo(AgentToolProjectionUseCase.Status.COMPLETED);
    }

    @Test
    void aliasesAreStableAcrossRepeatedProjectionsOfTheSameCatalogAndPolicy() {
        Fixture fixture = Fixture.with(
                AgentTestFixtures.manifest("orders.a.query", RiskLevel.READ_ONLY, "A"),
                AgentTestFixtures.manifest("orders.b.query", RiskLevel.READ_ONLY, "B"));

        List<String> first = aliases(fixture.useCase.project(RequestContext.empty(), GENEROUS));
        List<String> second = aliases(fixture.useCase.project(RequestContext.empty(), GENEROUS));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void unhealthyPolicySnapshotFailsClosedWithoutTouchingTheAliasIndex() {
        Fixture fixture = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        when(fixture.authorization.resolvePolicySnapshot(fixture.principal))
                .thenReturn(PolicySnapshot.unavailable(3L));

        AgentToolProjectionUseCase.ProjectionResult result =
                fixture.useCase.project(RequestContext.empty(), GENEROUS);

        assertThat(result.status()).isEqualTo(AgentToolProjectionUseCase.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("POLICY_UNAVAILABLE");
        assertThat(result.tools()).isEmpty();
    }

    @Test
    void failedAuthenticationNeverReachesAuthorization() {
        Fixture fixture = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        when(fixture.authentication.authenticate(any())).thenReturn(null);

        AgentToolProjectionUseCase.ProjectionResult result =
                fixture.useCase.project(RequestContext.empty(), GENEROUS);
        AgentToolProjectionUseCase.BindResult bound = fixture.useCase.bind(
                RequestContext.empty(), "cap_any", "turn-1", "request-1");

        assertThat(result.errorCode()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(bound.errorCode()).isEqualTo("AUTHENTICATION_FAILED");
        verify(fixture.authorization, never()).resolvePolicySnapshot(any());
    }

    @Test
    void invisibleCapabilitiesAreNeitherProjectedNorBindable() {
        Fixture fixture = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        String alias = fixture.useCase.project(RequestContext.empty(), GENEROUS)
                .tools().get(0).alias();
        when(fixture.authorization.resolvePolicySnapshot(fixture.principal))
                .thenReturn(PolicySnapshot.from(
                        CapabilityVisibility.restricted(5L, java.util.Set.of())));

        AgentToolProjectionUseCase.ProjectionResult projected =
                fixture.useCase.project(RequestContext.empty(), GENEROUS);
        AgentToolProjectionUseCase.BindResult bound = fixture.useCase.bind(
                RequestContext.empty(), alias, "turn-1", "request-1");

        assertThat(projected.tools()).isEmpty();
        assertThat(bound.errorCode()).isEqualTo("CAPABILITY_UNAVAILABLE");
    }

    private static List<String> aliases(
            AgentToolProjectionUseCase.ProjectionResult result) {
        return result.tools().stream()
                .map(AgentToolProjectionUseCase.ProjectedTool::alias)
                .toList();
    }

    /** Wires a real catalog manager, turn store and alias generator around mocked ports. */
    private record Fixture(AgentToolProjectionUseCase useCase,
                           AuthenticationPort authentication,
                           AuthorizationPort authorization,
                           AgentTurnStore turnStore,
                           Principal principal) {

        static Fixture with(CapabilityManifest... manifests) {
            AuthenticationPort authentication = mock(AuthenticationPort.class);
            AuthorizationPort authorization = mock(AuthorizationPort.class);
            CatalogPort catalog = mock(CatalogPort.class);
            Principal principal = AgentTestFixtures.principal("user-1");
            when(authentication.authenticate(any())).thenReturn(principal);
            when(authorization.resolvePolicySnapshot(principal))
                    .thenReturn(PolicySnapshot.from(CapabilityVisibility.all(5L)));
            when(authorization.authorizeExecution(eq(principal), anyString(), anyString()))
                    .thenReturn(true);
            when(catalog.loadCurrentSnapshot("production"))
                    .thenReturn(AgentTestFixtures.snapshot(9L, manifests));
            InMemoryCatalogManager manager = new InMemoryCatalogManager(catalog);
            assertThat(manager.loadAndActivate("production")).isTrue();
            AgentTurnStore turnStore = new InMemoryAgentTurnStore(
                    100, mock(TelemetryPort.class));
            AgentToolProjectionUseCase useCase = new AgentToolProjectionUseCase(
                    authentication, authorization, manager,
                    new ToolReferenceService("k1",
                            "projection-key-projection-key-0001"
                                    .getBytes(StandardCharsets.UTF_8),
                            null, null, 120L),
                    turnStore, new AliasGenerator(),
                    CapabilityProjectionRanker.lexicographic(), mock(TelemetryPort.class));
            return new Fixture(useCase, authentication, authorization, turnStore, principal);
        }
    }
}
