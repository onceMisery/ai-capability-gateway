package com.ai.gateway.adapter.a2a;

import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.TrustTier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests for gateway-side peer trust resolution (design §3.6).
 *
 * <p>The registry is the only place that decides a peer's trust tier, so the tests pin the
 * three defaults that make "authorize before expose" hold on A2A: no credential means
 * untrusted, an authenticated-but-unregistered peer is read-only rather than promoted on
 * demand, and nothing a peer says about itself — its declared agent name in particular —
 * can change the outcome.</p>
 *
 * @author cmiracle@163.com
 */
class A2aPeerTrustRegistryTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private static final String TRUSTED_TOKEN = "trusted-peer-token";
    private static final String UNKNOWN_TOKEN = "unregistered-peer-token";

    @Test
    void aRequestWithoutABearerCredentialIsUntrusted() {
        AgentIdentity identity = registry().identify(RequestContext.empty());

        assertThat(identity.trustTier()).isEqualTo(TrustTier.UNTRUSTED);
        // 未认证连接也必须得到形态合法的摘要，否则身份记录根本无法构造。
        assertThat(identity.peerDigest()).matches("[0-9a-f]{64}");
    }

    @Test
    void anAuthenticatedButUnregisteredPeerIsReadOnlyNotPromoted() {
        AgentIdentity identity = registry().identify(bearer(UNKNOWN_TOKEN));

        // 新接入方在完成注册之前只能读，这是默认结果而不是异常分支。
        assertThat(identity.trustTier()).isEqualTo(TrustTier.READ_ONLY);
        assertThat(identity.peerDigest())
                .isEqualTo(A2aPeerTrustRegistry.sha256(UNKNOWN_TOKEN));
    }

    @Test
    void aRegisteredPeerGetsItsConfiguredTierAndTheRegisteredIdAsAuditLabel() {
        AgentIdentity identity = registry().identify(headers(Map.of(
                "Authorization", "Bearer " + TRUSTED_TOKEN,
                A2aPeerTrustRegistry.PEER_NAME_HEADER, "i-am-the-orchestrator")));

        assertThat(identity.trustTier()).isEqualTo(TrustTier.TRUSTED_CONFIRMATION);
        // 自报名称永不参与判定，且在档案存在时被注册标识覆盖：审计里应当出现网关认得的标识。
        assertThat(identity.peerAgentName()).isEqualTo("orchestrator");
    }

    @Test
    void aDeclaredAgentNameCannotRaiseTheTierOfAnUnregisteredPeer() {
        AgentIdentity identity = registry().identify(headers(Map.of(
                "Authorization", "Bearer " + UNKNOWN_TOKEN,
                A2aPeerTrustRegistry.PEER_NAME_HEADER, "orchestrator")));

        assertThat(identity.trustTier()).isEqualTo(TrustTier.READ_ONLY);
        assertThat(identity.peerAgentName()).isEqualTo("orchestrator");
    }

    @Test
    void anExpiredProfileFallsBackToReadOnlyRatherThanUntrusted() {
        A2aPeerTrustRegistry registry = new A2aPeerTrustRegistry(List.of(profile(
                "orchestrator", TRUSTED_TOKEN, TrustTier.TRUSTED_CONFIRMATION,
                NOW.minusSeconds(1))), fixedClock());

        AgentIdentity identity = registry.identify(bearer(TRUSTED_TOKEN));

        // 凭据本身仍然通过了认证，失效的只是信任档案，因此落到「已认证但未注册」这一档。
        assertThat(identity.trustTier()).isEqualTo(TrustTier.READ_ONLY);
        assertThat(registry.profile(bearer(TRUSTED_TOKEN))).isEmpty();
    }

    @Test
    void aDisabledProfileIsTreatedAsAbsentForEveryDerivedDecision() {
        A2aPeerTrustRegistry registry = new A2aPeerTrustRegistry(List.of(
                new A2aPeerTrustProfile("orchestrator",
                        A2aPeerTrustRegistry.sha256(TRUSTED_TOKEN),
                        TrustTier.TRUSTED_CONFIRMATION, A2aIdentityMode.ON_BEHALF_OF,
                        null, null, 7, false, null)), fixedClock());

        assertThat(registry.identify(bearer(TRUSTED_TOKEN)).trustTier())
                .isEqualTo(TrustTier.READ_ONLY);
        // 委托深度也必须回落到默认上限，而不是继续沿用一份已被禁用的档案里的宽松配置。
        assertThat(registry.maxDelegationDepth(bearer(TRUSTED_TOKEN))).isEqualTo(3);
    }

    @Test
    void anUnregisteredPeerIsStillBoundByTheDefaultDelegationDepth() {
        A2aPeerTrustRegistry registry = registry();

        // 未注册 peer 恰恰是最需要被委托上限约束的一方，「未命中」不能等于「不限制」。
        assertThat(registry.maxDelegationDepth(bearer(UNKNOWN_TOKEN))).isEqualTo(3);
        assertThat(registry.maxDelegationDepth(RequestContext.empty())).isEqualTo(3);
        assertThat(registry.maxDelegationDepth(bearer(TRUSTED_TOKEN))).isEqualTo(2);
    }

    @Test
    void identityModeDefaultsToOnBehalfOfForPeersWithoutAProfile() {
        assertThat(registry().identityMode(bearer(UNKNOWN_TOKEN)))
                .isEqualTo(A2aIdentityMode.ON_BEHALF_OF);
    }

    @Test
    void aDisabledRegistryLeavesEveryAuthenticatedPeerReadOnly() {
        AgentIdentity identity = A2aPeerTrustRegistry.disabled().identify(bearer(TRUSTED_TOKEN));

        assertThat(identity.trustTier()).isEqualTo(TrustTier.READ_ONLY);
    }

    @Test
    void twoProfilesSharingOneFingerprintAreRejectedAtConstruction() {
        List<A2aPeerTrustProfile> duplicates = List.of(
                profile("orchestrator", TRUSTED_TOKEN, TrustTier.READ_ONLY, null),
                profile("another", TRUSTED_TOKEN, TrustTier.TRUSTED_CONFIRMATION, null));

        // 同一指纹两份档案时无法判断哪一份是意图，静默取其一会让分级变得不可预测。
        assertThatThrownBy(() -> new A2aPeerTrustRegistry(duplicates, fixedClock()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate A2A peer token fingerprint");
    }

    @Test
    void aServiceAccountProfileRequiresBothATenantAndAnExplicitWhitelist() {
        assertThatThrownBy(() -> new A2aPeerTrustProfile("batch",
                A2aPeerTrustRegistry.sha256("batch-token"), TrustTier.READ_ONLY,
                A2aIdentityMode.SERVICE_ACCOUNT, null, Set.of("orders.detail.query"),
                3, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceAccountOrgId");

        assertThatThrownBy(() -> new A2aPeerTrustProfile("batch",
                A2aPeerTrustRegistry.sha256("batch-token"), TrustTier.READ_ONLY,
                A2aIdentityMode.SERVICE_ACCOUNT, 7L, Set.of(), 3, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedCapabilityIds");
    }

    @Test
    void aServiceAccountCanNeverBeConfiguredAsTrustedForConfirmation() {
        // 该模式背后没有最终用户，也就没有人能承担确认动作；这个矛盾必须在启动期暴露。
        assertThatThrownBy(() -> new A2aPeerTrustProfile("batch",
                A2aPeerTrustRegistry.sha256("batch-token"),
                TrustTier.TRUSTED_CONFIRMATION, A2aIdentityMode.SERVICE_ACCOUNT,
                7L, Set.of("orders.detail.query"), 3, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRUSTED_CONFIRMATION");
    }

    @Test
    void theServiceAccountWhitelistConstrainsOnlyServiceAccounts() {
        A2aPeerTrustProfile serviceAccount = new A2aPeerTrustProfile("batch",
                A2aPeerTrustRegistry.sha256("batch-token"), TrustTier.READ_ONLY,
                A2aIdentityMode.SERVICE_ACCOUNT, 7L, Set.of("orders.detail.query"),
                3, true, null);
        A2aPeerTrustProfile onBehalfOf = profile(
                "orchestrator", TRUSTED_TOKEN, TrustTier.TRUSTED_CONFIRMATION, null);

        assertThat(serviceAccount.allowsCapability("orders.detail.query")).isTrue();
        assertThat(serviceAccount.allowsCapability("payments.settle")).isFalse();
        assertThat(serviceAccount.allowsCapability(null)).isFalse();
        // 代理调用模式下的能力范围由最终用户的真实授权决定，白名单不该在这里叠一层无关过滤。
        assertThat(onBehalfOf.allowsCapability("payments.settle")).isTrue();
    }

    @Test
    void aFingerprintThatIsNotASha256DigestIsRejected() {
        assertThatThrownBy(() -> new A2aPeerTrustProfile("orchestrator",
                "plaintext-token", TrustTier.READ_ONLY, A2aIdentityMode.ON_BEHALF_OF,
                null, null, 3, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    private static A2aPeerTrustRegistry registry() {
        return new A2aPeerTrustRegistry(List.of(profile("orchestrator", TRUSTED_TOKEN,
                TrustTier.TRUSTED_CONFIRMATION, null)), fixedClock());
    }

    private static A2aPeerTrustProfile profile(String peerId, String token,
                                               TrustTier trustTier, Instant expiresAt) {
        return new A2aPeerTrustProfile(peerId, A2aPeerTrustRegistry.sha256(token), trustTier,
                A2aIdentityMode.ON_BEHALF_OF, null, null, 2, true, expiresAt);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static RequestContext bearer(String token) {
        return new RequestContext(Map.of("Authorization", "Bearer " + token),
                Map.of(), Map.of(), "10.0.0.1");
    }

    private static RequestContext headers(Map<String, String> headers) {
        return new RequestContext(headers, Map.of(), Map.of(), "10.0.0.1");
    }
}
