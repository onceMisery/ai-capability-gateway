package com.ai.gateway.adapter.a2a;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the inbound selection mode (design §3.4.1, §3.11).
 *
 * <p>The mode decides <em>who</em> picks the capability, never <em>whether</em> the pick is
 * re-authorized. These tests therefore pin the two things a mis-set mode would break: the NL
 * router coupling that only one mode has, and the declared media types, which must never
 * promise a form the mode will reject.</p>
 *
 * @author cmiracle@163.com
 */
class A2aSelectionModeTest {

    @Test
    void onlyTheCompatibilityModeIsCoupledToTheNlRouter() {
        // 默认模式必须能在 NL 路由完全关闭的部署里工作，否则「零 LLM 调用」只是一句声明。
        assertThat(A2aSelectionMode.DELEGATED_SELECTION.requiresNlRouter()).isFalse();
        assertThat(A2aSelectionMode.STRUCTURED_ONLY.requiresNlRouter()).isFalse();
        assertThat(A2aSelectionMode.GATEWAY_SELECTION.requiresNlRouter()).isTrue();
    }

    @Test
    void everyModeAcceptsAtLeastOneRequestForm() {
        for (A2aSelectionMode mode : A2aSelectionMode.values()) {
            // 两个开关同时为 false 的模式会让对端无论怎么发都被拒，等于一个静默不可用的暴露面。
            assertThat(mode.acceptsFreeText() || mode.acceptsStructuredSelection())
                    .as("mode %s must accept some request form", mode)
                    .isTrue();
        }
    }

    @Test
    void theStrictestModeRefusesFreeTextAndSaysSoOnTheCard() {
        assertThat(A2aSelectionMode.STRUCTURED_ONLY.acceptsFreeText()).isFalse();
        assertThat(A2aSelectionMode.STRUCTURED_ONLY.inputModes())
                .containsExactly("application/json");
    }

    @Test
    void modesThatAcceptFreeTextDeclareBothMediaTypes() {
        assertThat(A2aSelectionMode.DELEGATED_SELECTION.inputModes())
                .containsExactly("text/plain", "application/json");
        assertThat(A2aSelectionMode.GATEWAY_SELECTION.inputModes())
                .containsExactly("text/plain", "application/json");
    }

    @Test
    void outputModesAreIdenticalAcrossModesBecauseTheResultShapeDoesNotVary() {
        for (A2aSelectionMode mode : A2aSelectionMode.values()) {
            assertThat(mode.outputModes()).containsExactly("application/json", "text/plain");
        }
    }

    @Test
    void anUnrecognisedConfigValueFallsBackToTheModeThatNeedsNoLlm() {
        // 落到需要 NL 路由的模式会让一次配置手误把 LLM 依赖引入本不需要它的部署。
        assertThat(A2aSelectionMode.from(null))
                .isEqualTo(A2aSelectionMode.DELEGATED_SELECTION);
        assertThat(A2aSelectionMode.from("  "))
                .isEqualTo(A2aSelectionMode.DELEGATED_SELECTION);
        assertThat(A2aSelectionMode.from("gateway"))
                .isEqualTo(A2aSelectionMode.DELEGATED_SELECTION);
    }

    @Test
    void configValuesAreParsedRegardlessOfCaseAndSeparatorStyle() {
        assertThat(A2aSelectionMode.from("structured-only"))
                .isEqualTo(A2aSelectionMode.STRUCTURED_ONLY);
        assertThat(A2aSelectionMode.from(" Gateway_Selection "))
                .isEqualTo(A2aSelectionMode.GATEWAY_SELECTION);
    }
}
