package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.a2a.A2aAgentEndpointResolver;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests for the outbound A2A assembly decisions (design §3.7, §3.8).
 *
 * <p>与 {@link A2aConfigurationTest} 同一风格：只断言无需 Spring 容器即可到达的纯判定——
 * 出站门禁、平面审计门禁与端点解析。这三处恰好是「一个配置错误会变成一个方向被静默打开
 * 或一次无痕对外委托」的位置。</p>
 *
 * @author cmiracle@163.com
 */
class A2aClientConfigurationTest {

    // ------------------------------------------------------------ 出站装配门禁

    @Test
    void theClientIsAssembledOnlyWhenTheSwitchAndTheModeBothSayOutbound() {
        assertThat(clientEnabled(true, "FULL")).isTrue();
        assertThat(clientEnabled(true, "CLIENT_ONLY")).isTrue();
        // 只做 A2A Server 的部署不该持有出站客户端：入站是暴露面，出站是依赖面。
        assertThat(clientEnabled(true, "SERVER_ONLY")).isFalse();
        assertThat(clientEnabled(true, "DISABLED")).isFalse();
    }

    @Test
    void theSwitchAloneNeverOpensAnOutboundDirection() {
        assertThat(clientEnabled(false, "FULL")).isFalse();
        assertThat(clientEnabled(false, "CLIENT_ONLY")).isFalse();
    }

    @Test
    void anUnreadableModeOpensNoDirectionAtAll() {
        assertThat(clientEnabled(true, null)).isFalse();
        assertThat(clientEnabled(true, "  ")).isFalse();
        assertThat(clientEnabled(true, "client-ish")).isFalse();
    }

    @Test
    void theRawPropertyFormAgreesWithTheBoundForm() {
        assertThat(A2aClientConfiguration.ClientEnabledCondition
                .clientEnabled("true", "CLIENT_ONLY")).isTrue();
        assertThat(A2aClientConfiguration.ClientEnabledCondition
                .clientEnabled(null, "CLIENT_ONLY")).isFalse();
        assertThat(A2aClientConfiguration.ClientEnabledCondition
                .clientEnabled("yes", "CLIENT_ONLY")).isFalse();
    }

    @Test
    void theTwoDirectionsAreIndependentRatherThanMutuallyExclusive() {
        // FULL 档两个方向都成立；两者共用一个开关，早晚会让某个部署多出一个没人知道的方向。
        assertThat(A2aConfiguration.ServerEnabledCondition.serverEnabled(true, "FULL")).isTrue();
        assertThat(clientEnabled(true, "FULL")).isTrue();
    }

    private static boolean clientEnabled(boolean enabled, String mode) {
        return A2aClientConfiguration.ClientEnabledCondition.clientEnabled(enabled, mode);
    }

    // ------------------------------------------------------------ 平面审计门禁

    @Test
    void theAuditOutletExistsWheneverEitherDirectionExists() {
        assertThat(planeEnabled(true, "FULL")).isTrue();
        assertThat(planeEnabled(true, "SERVER_ONLY")).isTrue();
        // CLIENT_ONLY 的部署必须拿到审计出口，否则出站委托要么无痕发生、要么装配失败。
        assertThat(planeEnabled(true, "CLIENT_ONLY")).isTrue();
    }

    @Test
    void noAuditOutletIsAssembledForADeploymentWithNoA2aPlaneAtAll() {
        assertThat(planeEnabled(true, "DISABLED")).isFalse();
        assertThat(planeEnabled(false, "FULL")).isFalse();
        assertThat(planeEnabled(true, "nonsense")).isFalse();
    }

    @Test
    void theAuditGateIsNeverNarrowerThanTheUnionOfTheTwoDirections() {
        // 刻意不写成 mode != DISABLED：按语义属性取并集，
        // 将来新增一种承载模式时不会静默漏掉审计出口。
        for (String mode : new String[] {"FULL", "SERVER_ONLY", "CLIENT_ONLY", "DISABLED"}) {
            boolean anyDirection = A2aConfiguration.ServerEnabledCondition.serverEnabled(true, mode)
                    || clientEnabled(true, mode);
            assertThat(planeEnabled(true, mode)).isEqualTo(anyDirection);
        }
    }

    private static boolean planeEnabled(boolean enabled, String mode) {
        return A2aAuditConfiguration.PlaneEnabledCondition.planeEnabled(enabled, mode);
    }

    // ------------------------------------------------------------ 端点解析

    @Test
    void aConfiguredReferenceKeyResolvesToItsOperatorSuppliedAddress() {
        A2aAgentEndpointResolver resolver = resolver("orders-agent", "  https://orders.internal/a2a ");

        assertThat(resolver.resolve("orders-agent"))
                .isEqualTo(URI.create("https://orders.internal/a2a"));
    }

    @Test
    void anUnconfiguredReferenceKeyThrowsInsteadOfResolvingToNothing() {
        // 返回 null 只会把一次配置缺失推迟成调用时刻一个更难归因的空指针。
        A2aAgentEndpointResolver resolver = resolver("orders-agent", "https://orders.internal/a2a");

        assertThatThrownBy(() -> resolver.resolve("unknown-agent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-agent");
        assertThatThrownBy(() -> resolver("blank-agent", "   ").resolve("blank-agent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNonHttpAddressIsRefusedRatherThanInterpretedByTheUriImplementation() {
        // 出站目标必须是一个明确的网络地址，而不是被某个 URI 实现宽容解释出来的东西。
        assertThatThrownBy(() -> resolver("local", "file:///etc/passwd").resolve("local"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http(s)");
        assertThatThrownBy(() -> resolver("local", "orders.internal/a2a").resolve("local"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static A2aAgentEndpointResolver resolver(String agentRef, String endpoint) {
        GatewayProperties properties = new GatewayProperties();
        properties.getProtocol().getA2aAgentEndpoints().put(agentRef, endpoint);
        return new A2aClientConfiguration().a2aAgentEndpointResolver(properties);
    }
}
