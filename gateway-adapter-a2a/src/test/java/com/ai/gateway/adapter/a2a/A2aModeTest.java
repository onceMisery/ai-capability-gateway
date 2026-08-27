package com.ai.gateway.adapter.a2a;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the A2A runtime-mode and identity-mode config enums (design §3.6, §3.10).
 *
 * <p>Both enums are parsed from deployment configuration, so the property under test is what a
 * typo produces. A2A adds two independent exposure surfaces (inbound and outbound) and one
 * identity decision; a mis-parsed value must land on the narrow side of each, never open one
 * the operator did not ask for.</p>
 *
 * @author cmiracle@163.com
 */
class A2aModeTest {

    @Test
    void inboundAndOutboundAreIndependentSwitchesRatherThanOneFlag() {
        assertThat(A2aMode.SERVER_ONLY.serverEnabled()).isTrue();
        assertThat(A2aMode.SERVER_ONLY.clientEnabled()).isFalse();
        assertThat(A2aMode.CLIENT_ONLY.serverEnabled()).isFalse();
        assertThat(A2aMode.CLIENT_ONLY.clientEnabled()).isTrue();
        assertThat(A2aMode.FULL.serverEnabled()).isTrue();
        assertThat(A2aMode.FULL.clientEnabled()).isTrue();
    }

    @Test
    void theDisabledModeOpensNeitherSurface() {
        assertThat(A2aMode.DISABLED.serverEnabled()).isFalse();
        assertThat(A2aMode.DISABLED.clientEnabled()).isFalse();
    }

    @Test
    void anUnrecognisedRuntimeModeOpensNothing() {
        // A2A 是新增暴露面：配置写错时「什么都不开」是唯一安全的结果，
        // 需要拒绝启动的组合由生产配置校验器给出明确错误，而不是靠这里抛异常。
        assertThat(A2aMode.from(null)).isEqualTo(A2aMode.DISABLED);
        assertThat(A2aMode.from("")).isEqualTo(A2aMode.DISABLED);
        assertThat(A2aMode.from("server")).isEqualTo(A2aMode.DISABLED);
        assertThat(A2aMode.from("enabled")).isEqualTo(A2aMode.DISABLED);
    }

    @Test
    void runtimeModeValuesAreParsedRegardlessOfCaseAndSeparatorStyle() {
        assertThat(A2aMode.from("server-only")).isEqualTo(A2aMode.SERVER_ONLY);
        assertThat(A2aMode.from(" Client_Only ")).isEqualTo(A2aMode.CLIENT_ONLY);
        assertThat(A2aMode.from("full")).isEqualTo(A2aMode.FULL);
    }

    @Test
    void onlyTheDelegatedUserIdentityModeCanEverReachAWrite() {
        // 服务账号背后没有最终用户，没人能承担确认动作；允许它写等于把两阶段写降级成一阶段。
        assertThat(A2aIdentityMode.ON_BEHALF_OF.writeEligible()).isTrue();
        assertThat(A2aIdentityMode.SERVICE_ACCOUNT.writeEligible()).isFalse();
    }

    @Test
    void anUnrecognisedIdentityModeFallsBackToTheReadOnlySide() {
        assertThat(A2aIdentityMode.from(null)).isEqualTo(A2aIdentityMode.SERVICE_ACCOUNT);
        assertThat(A2aIdentityMode.from("delegated"))
                .isEqualTo(A2aIdentityMode.SERVICE_ACCOUNT);
        assertThat(A2aIdentityMode.from("on-behalf-of"))
                .isEqualTo(A2aIdentityMode.ON_BEHALF_OF);
    }
}
