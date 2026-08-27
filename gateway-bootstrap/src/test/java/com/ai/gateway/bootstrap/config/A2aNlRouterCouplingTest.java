package com.ai.gateway.bootstrap.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests for the one configuration coupling between the A2A inbound plane and the
 * natural-language routing kernel (design §3.10, §3.11).
 *
 * <p>Two of the three selection modes are self-contained; {@code GATEWAY_SELECTION} is not — it
 * runs the gateway's own restricted selection, which only exists when the LLM kernel is wired.
 * That makes the pair a startup-time property rather than a runtime one: a deployment that
 * combines them has no working inbound path at all, so failing on the first request would only
 * turn a configuration mistake into a traffic incident. The kernel itself is never retired —
 * it stays available as the catalog's diagnostic and regression facility — so this is a
 * combination check, not a deprecation.</p>
 *
 * @author cmiracle@163.com
 */
class A2aNlRouterCouplingTest {

    @Test
    void gatewaySelectionWithoutTheKernelIsRejectedBeforeAnyTrafficArrives() {
        MockEnvironment environment = development()
                .withProperty("gateway.runtime.nl-router.mode", "DISABLED")
                .withProperty("gateway.a2a.selection-mode", "GATEWAY_SELECTION");

        assertThatThrownBy(() -> ProductionConfigurationValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.a2a.selection-mode")
                .hasMessageContaining("GATEWAY_SELECTION");
    }

    @Test
    void theRejectionIsCaseInsensitiveBecauseYamlValuesAreWrittenByHand() {
        MockEnvironment environment = development()
                .withProperty("gateway.runtime.nl-router.mode", "DISABLED")
                .withProperty("gateway.a2a.selection-mode", "gateway_selection");

        assertThatThrownBy(() -> ProductionConfigurationValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDefaultSelectionModeNeedsNoKernelAtAll() {
        // 默认档零 LLM 调用，因此「关掉模型」与「开启 A2A 入站」是可以共存的部署形态。
        MockEnvironment environment = development()
                .withProperty("gateway.runtime.nl-router.mode", "DISABLED")
                .withProperty("gateway.a2a.selection-mode", "DELEGATED_SELECTION");

        assertThatCode(() -> ProductionConfigurationValidator.validate(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void theStructuredOnlyModeNeedsNoKernelEither() {
        MockEnvironment environment = development()
                .withProperty("gateway.runtime.nl-router.mode", "DISABLED")
                .withProperty("gateway.a2a.selection-mode", "STRUCTURED_ONLY");

        assertThatCode(() -> ProductionConfigurationValidator.validate(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void gatewaySelectionIsAcceptedWheneverTheKernelIsActuallyLoaded() {
        // COMPAT 档装配内核，因此该组合合法；这条同时说明校验针对的是「内核是否装配」，
        // 而不是「是否使用了 LLM 档位」——把 NL 链当成待退役特性就会写成后者。
        MockEnvironment environment = development()
                .withProperty("gateway.runtime.nl-router.mode", "COMPAT")
                .withProperty("gateway.a2a.selection-mode", "GATEWAY_SELECTION");

        assertThatCode(() -> ProductionConfigurationValidator.validate(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void aDiagnosticOnlyDeploymentStillCarriesTheKernelSoTheCombinationHolds() {
        // DIAGNOSTIC 档只对管理面开放，但内核仍然装配；这正是「LLM 作为诊断设施长期保留」
        // 与「运行面是否曝光」两件事被分开的地方。
        MockEnvironment environment = development()
                .withProperty("gateway.runtime.nl-router.mode", "DIAGNOSTIC")
                .withProperty("gateway.a2a.selection-mode", "GATEWAY_SELECTION");

        assertThatCode(() -> ProductionConfigurationValidator.validate(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void productionNeedsAnExplicitOptInBeforeInboundTrafficMayConsumeModelQuota() {
        MockEnvironment environment = production()
                .withProperty("gateway.a2a.mode", "SERVER_ONLY")
                .withProperty("gateway.a2a.public-url", "https://gateway.internal/a2a")
                .withProperty("gateway.a2a.selection-mode", "GATEWAY_SELECTION");

        assertThatThrownBy(() -> ProductionConfigurationValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-gateway-selection-in-production");
    }

    @Test
    void theOptInMakesTheProductionCombinationLegalWithoutWeakeningAnythingElse() {
        MockEnvironment environment = production()
                .withProperty("gateway.a2a.mode", "SERVER_ONLY")
                .withProperty("gateway.a2a.public-url", "https://gateway.internal/a2a")
                .withProperty("gateway.a2a.selection-mode", "GATEWAY_SELECTION")
                .withProperty("gateway.a2a.allow-gateway-selection-in-production", "true");

        assertThatCode(() -> ProductionConfigurationValidator.validate(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void theOptInIsIrrelevantWhenTheInboundPlaneIsNotEvenEnabled() {
        // 承载面关闭时不存在入站流量，此时要求一个放行开关只会制造无意义的启动失败。
        MockEnvironment environment = production()
                .withProperty("gateway.a2a.selection-mode", "GATEWAY_SELECTION");

        assertThatCode(() -> ProductionConfigurationValidator.validate(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void anEnabledInboundPlaneMustAdvertiseAReachableAddress() {
        MockEnvironment environment = development()
                .withProperty("gateway.a2a.mode", "SERVER_ONLY");

        assertThatThrownBy(() -> ProductionConfigurationValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.a2a.public-url");
    }

    @Test
    void anUnauthenticatedExtendedCardIsRefusedInProductionBecauseItLeaksTheCatalog() {
        MockEnvironment environment = production()
                .withProperty("gateway.a2a.mode", "FULL")
                .withProperty("gateway.a2a.public-url", "https://gateway.internal/a2a")
                .withProperty("gateway.a2a.extended-card-required", "false");

        assertThatThrownBy(() -> ProductionConfigurationValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extended-card-required");
    }

    @Test
    void aNonProductionDeploymentMayRelaxTheExtendedCardForLocalIntegration() {
        MockEnvironment environment = development()
                .withProperty("gateway.a2a.mode", "FULL")
                .withProperty("gateway.a2a.public-url", "http://localhost:8080/a2a")
                .withProperty("gateway.a2a.extended-card-required", "false");

        assertThatCode(() -> ProductionConfigurationValidator.validate(environment))
                .doesNotThrowAnyException();
    }

    private static MockEnvironment development() {
        return new MockEnvironment().withProperty("gateway.environment", "development");
    }

    private static MockEnvironment production() {
        return new MockEnvironment()
                .withProperty("gateway.environment", "production")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db:5432/gateway")
                .withProperty("spring.datasource.username", "gateway")
                .withProperty("spring.datasource.password", "db-password")
                .withProperty("gateway.auth.provider", "sa-token")
                .withProperty("gateway.auth.sa-token.jwt-secret-key",
                        "a-production-jwt-secret-with-at-least-32-bytes")
                .withProperty("gateway.auth.console-admin.username", "gateway-admin")
                .withProperty("gateway.auth.console-admin.password", "a-strong-admin-password")
                .withProperty("gateway.cache.provider", "redis")
                .withProperty("gateway.redis.address", "redis://redis:6379")
                .withProperty("gateway.ratelimit.provider", "sentinel")
                .withProperty("gateway.llm.endpoint", "https://llm.example.com/v1")
                .withProperty("gateway.llm.api-key", "llm-secret")
                .withProperty("gateway.llm.model", "gateway-model")
                .withProperty("dubbo.registry.address", "nacos://nacos:8848")
                .withProperty("gateway.operation.confirmation-secret",
                        "a-production-confirm-secret-at-least-32-bytes")
                .withProperty("gateway.agent.tool-ref-secret",
                        "a-production-agent-tool-ref-secret-at-least-32-bytes");
    }
}
