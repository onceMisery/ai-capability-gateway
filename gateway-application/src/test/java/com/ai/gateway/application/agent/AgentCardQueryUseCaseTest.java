package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.application.catalog.LuceneCandidateRetriever;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityReference;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.TrustTier;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acceptance tests for the tiered card query use case (design §3.4, §3.11).
 *
 * <p>Two properties are load-bearing here and neither is visible from the projection service
 * alone. First, <b>lease discipline</b>: the extended card reads the runtime catalog view, and
 * that read must finish before the lease is returned — otherwise a concurrent rotation can
 * close the Lucene index mid-read. Second, <b>indistinguishable unavailability</b>: failed
 * authentication, an unready catalog, and an unhealthy policy snapshot all collapse into an
 * empty result, because any observable difference between them turns an authorization outcome
 * into probeable information.</p>
 *
 * @author cmiracle@163.com
 */
class AgentCardQueryUseCaseTest {

    private static final AgentIdentity PEER = new AgentIdentity(
            "orchestrator", "a".repeat(64), TrustTier.TRUSTED_CONFIRMATION);

    @Test
    void publicCardTouchesNeitherAuthenticationNorAuthorizationNorTheCatalog() {
        Fixture fixture = Fixture.withoutCatalog();

        AgentCardProjection card = fixture.useCase.publicCard();

        // 公开卡是失效关闭的回退目标，它必须在目录与授权都不可用时依然可用；
        // 一旦它依赖运行面状态，回退本身也会一起失效。
        assertThat(card.skills()).isEmpty();
        assertThat(card.agentName()).isEqualTo("capability-gateway");
        verify(fixture.authentication, never()).authenticate(any());
        verify(fixture.authorization, never()).resolvePolicySnapshot(any());
    }

    @Test
    void extendedCardProjectsTheDomainsCarriedByTheActiveView() {
        Fixture fixture = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));

        Optional<AgentCardProjection> card =
                fixture.useCase.extendedCard(PEER, RequestContext.empty());

        assertThat(card).isPresent();
        assertThat(card.get().skills())
                .extracting(AgentCardProjection.SkillProjection::id)
                .containsExactly("domain.orders");
    }

    @Test
    void restrictedVisibilityIsAppliedBeforeAggregationSoUnauthorizedDomainsNeverAppear() {
        Fixture fixture = Fixture.with(
                AgentTestFixtures.manifest("orders.detail.query", RiskLevel.READ_ONLY,
                        "Query one order"),
                AgentTestFixtures.manifest("users.profile.query", RiskLevel.READ_ONLY,
                        "Query one user profile"));
        when(fixture.authorization.resolvePolicySnapshot(fixture.principal))
                .thenReturn(PolicySnapshot.from(CapabilityVisibility.restricted(5L,
                        Set.of(new CapabilityReference("orders.detail.query", "1.0.0")))));

        Optional<AgentCardProjection> card =
                fixture.useCase.extendedCard(PEER, RequestContext.empty());

        // 未授权的域名不得出现在任何字段里——卡片是曝光动作，域名本身就是目录结构信息。
        assertThat(card).isPresent();
        assertThat(card.get().skills())
                .extracting(AgentCardProjection.SkillProjection::id)
                .containsExactly("domain.orders");
        assertThat(card.get().toString()).doesNotContain("users");
    }

    @Test
    void failedAuthenticationYieldsEmptyWithoutEverConsultingAuthorization() {
        Fixture fixture = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        when(fixture.authentication.authenticate(any())).thenReturn(null);

        Optional<AgentCardProjection> card =
                fixture.useCase.extendedCard(PEER, RequestContext.empty());

        // 顺序不可交换：未认证的调用方不该触发任何一次授权解析，
        // 否则「谁在探测」与「探测了什么」都会写进授权侧的可观测面。
        assertThat(card).isEmpty();
        verify(fixture.authorization, never()).resolvePolicySnapshot(any());
    }

    @Test
    void anAuthenticationFailureIsIndistinguishableFromAnUnreadyCatalog() {
        Fixture authFailed = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        when(authFailed.authentication.authenticate(any())).thenReturn(null);
        Fixture noCatalog = Fixture.withoutCatalog();

        Optional<AgentCardProjection> byAuth =
                authFailed.useCase.extendedCard(PEER, RequestContext.empty());
        Optional<AgentCardProjection> byCatalog =
                noCatalog.useCase.extendedCard(PEER, RequestContext.empty());

        // 两条完全不同的失败路径必须产生同一个外部结果。
        assertThat(byAuth).isEqualTo(byCatalog).isEmpty();
    }

    @Test
    void anUnreadyCatalogNeverReachesAuthorizationEither() {
        Fixture fixture = Fixture.withoutCatalog();

        Optional<AgentCardProjection> card =
                fixture.useCase.extendedCard(PEER, RequestContext.empty());

        // 目录未就绪时不必解析授权：省下的不是一次调用，而是一条「目录状态可由授权侧侧信道推断」的路径。
        assertThat(card).isEmpty();
        verify(fixture.authorization, never()).resolvePolicySnapshot(any());
    }

    @Test
    void infrastructureFailureOfEitherPortDegradesToUnavailableRatherThanPropagating() {
        Fixture authThrows = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        when(authThrows.authentication.authenticate(any()))
                .thenThrow(new IllegalStateException("auth backend down"));
        Fixture policyThrows = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        when(policyThrows.authorization.resolvePolicySnapshot(policyThrows.principal))
                .thenThrow(new IllegalStateException("policy store down"));

        // 基础设施抖动必须收敛成「不可用」：向上抛异常会让传输层被迫决定怎么处理，
        // 而那正是安全判定不该落到传输层的地方。
        assertThat(authThrows.useCase.extendedCard(PEER, RequestContext.empty())).isEmpty();
        assertThat(policyThrows.useCase.extendedCard(PEER, RequestContext.empty())).isEmpty();
    }

    @Test
    void anUnhealthyPolicySnapshotFailsClosedAndIsRecordedForOperators() {
        Fixture fixture = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        when(fixture.authorization.resolvePolicySnapshot(fixture.principal))
                .thenReturn(PolicySnapshot.unavailable(3L));

        Optional<AgentCardProjection> card =
                fixture.useCase.extendedCard(PEER, RequestContext.empty());

        // 对端看不出区别，运维必须看得出：不可区分是对外契约，不是对内的信息损失。
        assertThat(card).isEmpty();
        verify(fixture.telemetry).increment("gateway.agent.card.unavailable",
                Map.of("stage", "policy"));
    }

    @Test
    void aNonPositivePolicyEpochIsTreatedAsUnavailableNotAsAnEmptyVisibleSet() {
        Fixture fixture = Fixture.with(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        when(fixture.authorization.resolvePolicySnapshot(fixture.principal))
                .thenReturn(PolicySnapshot.from(CapabilityVisibility.all(0L)));

        Optional<AgentCardProjection> card =
                fixture.useCase.extendedCard(PEER, RequestContext.empty());

        // 纪元是撤权的唯一生效机制。纪元非法说明授权结论无法定序，
        // 此时把它当成「全可见」比当成「不可用」危险得多。
        assertThat(card).isEmpty();
    }

    @Test
    void theCatalogLeaseIsReleasedSoTheNextRotationCanReclaimTheRetiredIndex() {
        Fixture fixture = Fixture.withIndexedCatalog(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        ActiveCatalogView retiring = fixture.manager.getActiveView();
        assertThat(retiring.indexHandle()).isNotNull();

        assertThat(fixture.useCase.extendedCard(PEER, RequestContext.empty())).isPresent();
        when(fixture.catalog.loadCurrentSnapshot("production"))
                .thenReturn(AgentTestFixtures.snapshot(10L, AgentTestFixtures.manifest(
                        "orders.detail.query", RiskLevel.READ_ONLY, "Query one order")));
        boolean rotated = fixture.manager.loadAndActivate("production");

        // 这条断言是本用例存在的全部理由。租约若被泄漏，v9 会带着未归还的租约进入退休队列，
        // 其 Lucene 句柄永不关闭，而下一次目录切换会被 retired_view_busy 直接拒绝——
        // 也就是说一次卡片查询足以让整个网关再也无法完成目录轮换。
        assertThat(rotated).isTrue();
        assertThat(retiring.indexHandle().isClosed()).isTrue();
    }

    @Test
    void aRetiredViewIsNeverProjectedFromBecauseTheLeaseCannotBeAcquired() {
        Fixture fixture = Fixture.withIndexedCatalog(AgentTestFixtures.manifest(
                "orders.detail.query", RiskLevel.READ_ONLY, "Query one order"));
        ActiveCatalogView retiring = fixture.manager.getActiveView();
        when(fixture.catalog.loadCurrentSnapshot("production"))
                .thenReturn(AgentTestFixtures.snapshot(10L,
                        AgentTestFixtures.manifest("orders.detail.query", RiskLevel.READ_ONLY,
                                "Query one order"),
                        AgentTestFixtures.manifest("users.profile.query", RiskLevel.READ_ONLY,
                                "Query one user profile")));
        assertThat(fixture.manager.loadAndActivate("production")).isTrue();

        Optional<AgentCardProjection> card =
                fixture.useCase.extendedCard(PEER, RequestContext.empty());

        // 轮换之后的查询必须落到新一代视图上：读到已退休的那一代，
        // 意味着投影可能来自一份已经被撤销的目录。
        assertThat(retiring.indexHandle().isClosed()).isTrue();
        assertThat(card).isPresent();
        assertThat(card.get().skills())
                .extracting(AgentCardProjection.SkillProjection::id)
                .containsExactly("domain.orders", "domain.users");
    }

    @Test
    void everyCollaboratorIsRequiredSoNoDeploymentCanWireInAFailOpenBranch() {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        InMemoryCatalogManager manager = new InMemoryCatalogManager(mock(CatalogPort.class));
        AgentCardProjectionService projection = Fixture.cardService();
        TelemetryPort telemetry = mock(TelemetryPort.class);

        // 缺省任何一个协作者都不该退化成「跳过该步骤」，因此构造期就拒绝。
        assertThatThrownBy(() -> new AgentCardQueryUseCase(
                null, authorization, manager, projection, telemetry))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentCardQueryUseCase(
                authentication, null, manager, projection, telemetry))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentCardQueryUseCase(
                authentication, authorization, null, projection, telemetry))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentCardQueryUseCase(
                authentication, authorization, manager, null, telemetry))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentCardQueryUseCase(
                authentication, authorization, manager, projection, null))
                .isInstanceOf(NullPointerException.class);
    }

    /** Wires a real catalog manager and a real projection service around mocked ports. */
    private record Fixture(AgentCardQueryUseCase useCase,
                           AuthenticationPort authentication,
                           AuthorizationPort authorization,
                           CatalogPort catalog,
                           InMemoryCatalogManager manager,
                           TelemetryPort telemetry,
                           Principal principal) {

        /** A gateway whose catalog was never activated — the fail-closed baseline. */
        static Fixture withoutCatalog() {
            return build(null, null);
        }

        static Fixture with(CapabilityManifest... manifests) {
            return build(null, manifests);
        }

        /**
         * Activates the catalog through a real Lucene retriever.
         *
         * <p>Only then does the view own an index handle whose {@code isClosed()} flips when a
         * retired generation drains — which is the one observable proof that the use case
         * returned its lease.</p>
         */
        static Fixture withIndexedCatalog(CapabilityManifest... manifests) {
            return build(new LuceneCandidateRetriever(), manifests);
        }

        private static Fixture build(LuceneCandidateRetriever retriever,
                                    CapabilityManifest[] manifests) {
            AuthenticationPort authentication = mock(AuthenticationPort.class);
            AuthorizationPort authorization = mock(AuthorizationPort.class);
            CatalogPort catalog = mock(CatalogPort.class);
            TelemetryPort telemetry = mock(TelemetryPort.class);
            Principal principal = AgentTestFixtures.principal("user-1");
            when(authentication.authenticate(any())).thenReturn(principal);
            when(authorization.resolvePolicySnapshot(principal))
                    .thenReturn(PolicySnapshot.from(CapabilityVisibility.all(5L)));
            InMemoryCatalogManager manager = new InMemoryCatalogManager(
                    catalog, new CapabilityPublicProjectionService(), retriever);
            if (manifests != null) {
                when(catalog.loadCurrentSnapshot("production"))
                        .thenReturn(AgentTestFixtures.snapshot(9L, manifests));
                assertThat(manager.loadAndActivate("production")).isTrue();
            }
            AgentCardQueryUseCase useCase = new AgentCardQueryUseCase(
                    authentication, authorization, manager, cardService(), telemetry);
            return new Fixture(useCase, authentication, authorization, catalog, manager,
                    telemetry, principal);
        }

        private static AgentCardProjectionService cardService() {
            return new AgentCardProjectionService(
                    new AgentCardProjectionService.AgentDescriptor(
                            "capability-gateway", "受治理的企业能力执行平面",
                            "https://gateway.internal/a2a", "0.1.0"),
                    new CapabilityPublicProjectionService());
        }
    }
}
