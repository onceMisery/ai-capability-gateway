package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.a2a.A2aDelegatedRetrievalHandler;
import com.ai.gateway.adapter.a2a.A2aGatewaySelectionRetrievalHandler;
import com.ai.gateway.adapter.a2a.A2aIdentityMode;
import com.ai.gateway.adapter.a2a.A2aPeerTrustProfile;
import com.ai.gateway.adapter.a2a.A2aRateLimiter;
import com.ai.gateway.adapter.a2a.A2aRetrievalHandler;
import com.ai.gateway.adapter.a2a.A2aSelectionMode;
import com.ai.gateway.adapter.a2a.A2aTaskStateMapper;
import com.ai.gateway.application.agent.AgentCardProjectionService;
import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.domain.model.TrustTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Acceptance tests for the A2A assembly decisions (design §3.9, §3.10).
 *
 * <p>Only the pure decisions are asserted — mode gating, trust-profile mapping, retrieval-strategy
 * selection and descriptor construction. Those are exactly the places where a configuration
 * mistake turns into an exposure or a silently disabled control, and they are reachable without a
 * Spring context, which is how the rest of this module's configuration tests are written.</p>
 *
 * @author cmiracle@163.com
 */
class A2aConfigurationTest {

    private final A2aTaskStateMapper stateMapper = new A2aTaskStateMapper();

    // ------------------------------------------------------------ 装配门禁

    @Test
    void theServerIsAssembledOnlyWhenTheSwitchAndTheModeBothSayInbound() {
        assertThat(serverEnabled(true, "FULL")).isTrue();
        assertThat(serverEnabled(true, "SERVER_ONLY")).isTrue();
        // 只开出站的部署必须拿不到入站端点：出站是网关主动去调别人，与暴露面无关。
        assertThat(serverEnabled(true, "CLIENT_ONLY")).isFalse();
        assertThat(serverEnabled(true, "DISABLED")).isFalse();
    }

    @Test
    void theSwitchAloneNeverExposesTheEndpoints() {
        assertThat(serverEnabled(false, "FULL")).isFalse();
        assertThat(serverEnabled(false, "SERVER_ONLY")).isFalse();
    }

    @Test
    void anUnreadableModeFallsBackToNotExposingAnything() {
        // 新增暴露面的配置写错时，「什么都不开」是唯一安全的落点。
        assertThat(serverEnabled(true, null)).isFalse();
        assertThat(serverEnabled(true, "   ")).isFalse();
        assertThat(serverEnabled(true, "server-only-ish")).isFalse();
    }

    @Test
    void theRawPropertyFormAgreesWithTheBoundForm() {
        // 条件注解读的是原始字符串，限流规则读的是已绑定对象；两者判定必须同源，
        // 否则会出现「端点装配了但规则没注册」这种只在压测时暴露的组合。
        assertThat(A2aConfiguration.ServerEnabledCondition.serverEnabled("true", "FULL")).isTrue();
        assertThat(A2aConfiguration.ServerEnabledCondition.serverEnabled(null, "FULL")).isFalse();
        assertThat(A2aConfiguration.ServerEnabledCondition.serverEnabled("yes", "FULL")).isFalse();
    }

    private static boolean serverEnabled(boolean enabled, String mode) {
        return A2aConfiguration.ServerEnabledCondition.serverEnabled(enabled, mode);
    }

    // ------------------------------------------------ 未注册 peer 的默认身份模式

    @Test
    void theDefaultIdentityModeForUnregisteredPeersMustBeOnBehalfOf() {
        assertThatCode(() -> A2aConfiguration.assertUnregisteredPeerIdentityMode("ON_BEHALF_OF"))
                .doesNotThrowAnyException();
        assertThatCode(() -> A2aConfiguration.assertUnregisteredPeerIdentityMode("on-behalf-of"))
                .doesNotThrowAnyException();
    }

    @Test
    void aServiceAccountDefaultIsRefusedBecauseItWouldHaveNoTenantAndNoAllowList() {
        assertThatThrownBy(
                () -> A2aConfiguration.assertUnregisteredPeerIdentityMode("SERVICE_ACCOUNT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.a2a.identity-mode")
                .hasMessageContaining("peer-trust");
    }

    @Test
    void anUnreadableDefaultIdentityModeFailsTheStartupRatherThanNarrowingSilently() {
        // A2aIdentityMode.from 的失效关闭方向是「更窄的一侧」即服务账号，
        // 而在「未注册 peer 的默认身份」这个位置上，更窄恰恰是非法的——
        // 一个匿名对端会因此拿到不属于任何租户的执行身份。
        assertThatThrownBy(() -> A2aConfiguration.assertUnregisteredPeerIdentityMode(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> A2aConfiguration.assertUnregisteredPeerIdentityMode("  "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> A2aConfiguration.assertUnregisteredPeerIdentityMode("delegated"))
                .isInstanceOf(IllegalStateException.class)
                // 报文里带上配置原值，运维才能分辨自己踩的是「写错」还是「显式配了服务账号」。
                .hasMessageContaining("delegated");
    }

    // ------------------------------------------------------------ 信任档案映射

    /** 一个形态合法的 SHA-256 十六进制摘要；档案的紧凑构造器只接受这种形态。 */
    private static final String FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void aWellFormedEntryBecomesAProfileWithoutAnyWideningOfItsFields() {
        GatewayProperties.A2aPeerTrust configured = peerTrust();
        configured.setTrustTier("trusted-confirmation");
        configured.setExpiresAt("2030-01-01T00:00:00Z");

        A2aPeerTrustProfile profile = A2aConfiguration.toPeerTrustProfile(configured);

        assertThat(profile.peerId()).isEqualTo("peer-a");
        assertThat(profile.tokenFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(profile.trustTier()).isEqualTo(TrustTier.TRUSTED_CONFIRMATION);
        assertThat(profile.identityMode()).isEqualTo(A2aIdentityMode.ON_BEHALF_OF);
        assertThat(profile.expiresAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(profile.serviceAccountOrgId()).isNull();
    }

    @Test
    void anUnreadableEntryFailsTheStartupInsteadOfBeingSkipped() {
        // 跳过一条档案的后果是：运维以为某个 peer 已受信，实际它落在「未注册 ⇒ 只读」那一档，
        // 写操作会以一个看不出原因的拒绝告终，而配置文件里明明写着它被信任。
        GatewayProperties.A2aPeerTrust garbageTier = peerTrust();
        garbageTier.setTrustTier("very-trusted");
        assertThatThrownBy(() -> A2aConfiguration.toPeerTrustProfile(garbageTier))
                .isInstanceOf(IllegalStateException.class)
                // 报文必须点名是哪一条档案，否则运维只能逐条比对配置。
                .hasMessageContaining("peer-a");
    }

    @Test
    void aMistypedTenantNumberIsNotSwallowedIntoAMissingTenant() {
        // 若把写错的租户号吞成 null，服务账号档案只会报「缺租户号」，
        // 而真正的问题是那串数字打错了。
        GatewayProperties.A2aPeerTrust configured = peerTrust();
        configured.setServiceAccountOrgId("10O");
        assertThatThrownBy(() -> A2aConfiguration.toPeerTrustProfile(configured))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void aServiceAccountEntryWithoutATenantOrAnAllowListIsRefused() {
        GatewayProperties.A2aPeerTrust noTenant = peerTrust();
        noTenant.setIdentityMode("SERVICE_ACCOUNT");
        noTenant.setAllowedCapabilityIds(List.of("cap-1"));
        assertThatThrownBy(() -> A2aConfiguration.toPeerTrustProfile(noTenant))
                .isInstanceOf(IllegalStateException.class);

        GatewayProperties.A2aPeerTrust noAllowList = peerTrust();
        noAllowList.setIdentityMode("SERVICE_ACCOUNT");
        noAllowList.setServiceAccountOrgId("42");
        assertThatThrownBy(() -> A2aConfiguration.toPeerTrustProfile(noAllowList))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNullEntryIsNamedAsSuchRatherThanSurfacingAsANullPointer() {
        assertThatThrownBy(() -> A2aConfiguration.toPeerTrustProfile(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.a2a.peer-trust");
    }

    private static GatewayProperties.A2aPeerTrust peerTrust() {
        GatewayProperties.A2aPeerTrust configured = new GatewayProperties.A2aPeerTrust();
        configured.setPeerId("peer-a");
        configured.setTokenFingerprint(FINGERPRINT);
        return configured;
    }

    // ------------------------------------------------------------ 检索策略选择

    @Test
    void eachSelectionModeMapsToItsOwnRetrievalStrategy() {
        assertThat(retrievalHandler(A2aSelectionMode.DELEGATED_SELECTION))
                .isInstanceOf(A2aDelegatedRetrievalHandler.class);
        assertThat(retrievalHandler(A2aSelectionMode.GATEWAY_SELECTION))
                .isInstanceOf(A2aGatewaySelectionRetrievalHandler.class);
        assertThat(retrievalHandler(A2aSelectionMode.STRUCTURED_ONLY))
                .isNotInstanceOf(A2aDelegatedRetrievalHandler.class)
                .isNotInstanceOf(A2aGatewaySelectionRetrievalHandler.class);
    }

    @Test
    void theNaturalLanguageKernelIsTouchedOnlyByTheGatewaySelectionTier() {
        // 只有网关选择档需要 NL 内核。其余两档若也去取这个 Bean，
        // 「诊断用的 LLM 链路缺一个 Bean」就会连带让 A2A 入站整个装配不起来。
        assertThat(nlRouterInvoked(A2aSelectionMode.DELEGATED_SELECTION)).isFalse();
        assertThat(nlRouterInvoked(A2aSelectionMode.STRUCTURED_ONLY)).isFalse();
        assertThat(nlRouterInvoked(A2aSelectionMode.GATEWAY_SELECTION)).isTrue();
    }

    private A2aRetrievalHandler retrievalHandler(A2aSelectionMode mode) {
        return A2aConfiguration.retrievalHandler(new GatewayProperties.A2a(), mode, stateMapper,
                mock(AgentHostConnector.class), () -> mock(NaturalLanguageQueryUseCase.class));
    }

    private boolean nlRouterInvoked(A2aSelectionMode mode) {
        boolean[] touched = {false};
        Supplier<NaturalLanguageQueryUseCase> nlRouter = () -> {
            touched[0] = true;
            return mock(NaturalLanguageQueryUseCase.class);
        };
        A2aConfiguration.retrievalHandler(new GatewayProperties.A2a(), mode, stateMapper,
                mock(AgentHostConnector.class), nlRouter);
        return touched[0];
    }

    // ------------------------------------------------------------ 公开卡描述符

    @Test
    void theDescriptorCarriesNoInternalTopologyAndAlwaysCarriesAVersion() {
        GatewayProperties.A2a a2a = new GatewayProperties.A2a();
        a2a.setPublicUrl("https://gw.example.com");

        AgentCardProjectionService.AgentDescriptor descriptor =
                A2aConfiguration.agentDescriptor(a2a);

        assertThat(descriptor.agentName()).isEqualTo("capability-gateway");
        assertThat(descriptor.publicUrl()).isEqualTo("https://gw.example.com");
        // 版本取运行构件的清单值，测试期（类目录运行）落到兜底常量；两种情况都不允许为空——
        // 对端会拿它做兼容判断，一个空版本号比没有版本号更难排查。
        assertThat(descriptor.version()).isNotBlank();
        assertThat(descriptor.description()).isNotBlank()
                .doesNotContainIgnoringCase("dubbo")
                .doesNotContainIgnoringCase("spring");
    }

    @Test
    void aMissingPublicUrlIsRefusedRatherThanAnnouncedAsAnUnreachableAddress() {
        // publicUrl 为空的公开卡等于告诉对端「我在这里但你到不了」，比不宣告更难排查。
        assertThatThrownBy(() -> A2aConfiguration.agentDescriptor(new GatewayProperties.A2a()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theGatewayVersionIsNeverBlank() {
        assertThat(A2aConfiguration.gatewayVersion()).isNotBlank();
    }

    // ------------------------------------------------------------ 限流规则注册

    @Test
    void theThreeExposureDimensionsAreRegisteredUnderTheKeysTheLimiterActuallyUses() {
        GatewayProperties.A2a a2a = new GatewayProperties.A2a();
        a2a.setEnabled(true);
        a2a.setMode("FULL");
        a2a.setCardQps(50d);
        a2a.setTaskQps(100d);

        // 键必须来自限流器常量本身：规则名与取用名一旦各写一份，
        // 规则就会挂在一个没人访问的资源上——「配了阈值却从不生效」，且不报任何错。
        assertThat(SentinelRateLimitConfiguration.a2aDimensionQps(a2a))
                .containsOnlyKeys(A2aRateLimiter.PUBLIC_CARD, A2aRateLimiter.EXTENDED_CARD,
                        A2aRateLimiter.TASK)
                // 两张卡共用一个阈值但各占一个资源键，因此各自计数：
                // 匿名可达的公开卡被刷满时，受信 peer 的扩展卡请求不该跟着被拒。
                .containsEntry(A2aRateLimiter.PUBLIC_CARD, 50d)
                .containsEntry(A2aRateLimiter.EXTENDED_CARD, 50d)
                .containsEntry(A2aRateLimiter.TASK, 100d);
    }

    @Test
    void noRuleIsRegisteredForADeploymentThatHasNoInboundEndpoints() {
        // A2A 关闭的部署不存在这三个入口，此时校验它们的阈值只会把一个不影响任何行为的
        // 配置值变成启动失败（SentinelRuleInitializer 对非正阈值是硬失败的）。
        GatewayProperties.A2a disabled = new GatewayProperties.A2a();
        disabled.setTaskQps(0d);
        assertThat(SentinelRateLimitConfiguration.a2aDimensionQps(disabled)).isEmpty();

        GatewayProperties.A2a clientOnly = new GatewayProperties.A2a();
        clientOnly.setEnabled(true);
        clientOnly.setMode("CLIENT_ONLY");
        assertThat(SentinelRateLimitConfiguration.a2aDimensionQps(clientOnly)).isEmpty();
    }
}
