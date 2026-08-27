package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentCardProjection;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the neutral-projection → SDK card encoding (design §3.9).
 *
 * <p>The codec exists so the SDK type appears in exactly one place, and so the card's
 * transport and security attributes can be filled without letting them influence the
 * projection. These tests therefore pin two things: the encoding is faithful (nothing is
 * added to or dropped from the projected skill set), and the declared skill modes track what
 * the configured {@link A2aSelectionMode} actually accepts — a card that advertises
 * {@code text/plain} under a structured-only mode would invite requests that are certain to
 * be rejected.</p>
 *
 * @author cmiracle@163.com
 */
class AgentCardCodecTest {

    private static final AgentCardProjection PUBLIC_CARD = new AgentCardProjection(
            "capability-gateway", "受治理的企业能力执行平面",
            "https://gateway.internal/a2a", "0.1.0", true,
            List.of("text/plain", "application/json"),
            List.of("text/plain", "application/json"), List.of());

    @Test
    void aPublicProjectionEncodesToACardWithAnEmptySkillListNotNull() {
        AgentCard card = new AgentCardCodec().encode(PUBLIC_CARD);

        // SDK 对 skills 有非空断言，而公开卡的 skills 恒为空列表：两者必须同时成立。
        assertThat(card.skills()).isEmpty();
        assertThat(card.name()).isEqualTo("capability-gateway");
        assertThat(card.url()).isEqualTo("https://gateway.internal/a2a");
        assertThat(card.version()).isEqualTo("0.1.0");
        assertThat(card.supportsAuthenticatedExtendedCard()).isTrue();
    }

    @Test
    void theCodecNeitherAddsNorDropsSkillsAndPreservesOrder() {
        AgentCardProjection projection = withSkills(
                skill("domain.orders", "orders", "订单查询", List.of("read-only"),
                        List.of("查询订单当前状态")),
                skill("domain.users", "users", "用户查询", List.of("read-only"), List.of()));

        AgentCard card = new AgentCardCodec().encode(projection);

        // 编码器不做任何投影决策：可见面已经由 AgentCardProjectionService 决定完毕。
        assertThat(card.skills()).extracting(AgentSkill::id)
                .containsExactly("domain.orders", "domain.users");
        assertThat(card.skills().get(0).name()).isEqualTo("orders");
        assertThat(card.skills().get(0).description()).isEqualTo("订单查询");
        assertThat(card.skills().get(0).tags()).containsExactly("read-only");
        assertThat(card.skills().get(0).examples()).containsExactly("查询订单当前状态");
    }

    @Test
    void aDomainWithoutExamplesOmitsTheFieldRatherThanEmittingAnEmptyArray() {
        AgentCard card = new AgentCardCodec().encode(withSkills(
                skill("domain.users", "users", "用户查询", List.of("read-only"), List.of())));

        // 空数组会被对端读成「该域确实没有任何可用示例」，那是投影没产出示例之外的额外信息。
        assertThat(card.skills().get(0).examples()).isNull();
    }

    @Test
    void declaredSkillModesMatchWhatTheSelectionModeActuallyAccepts() {
        AgentCardProjection projection = withSkills(
                skill("domain.orders", "orders", "订单查询", List.of("read-only"), List.of()));

        AgentSkill delegated = new AgentCardCodec(
                AgentCardCodec.TransportProfile.defaults(),
                A2aSelectionMode.DELEGATED_SELECTION).encode(projection).skills().get(0);
        AgentSkill structuredOnly = new AgentCardCodec(
                AgentCardCodec.TransportProfile.defaults(),
                A2aSelectionMode.STRUCTURED_ONLY).encode(projection).skills().get(0);

        // 仅结构化模式会拒绝自由文本，卡片上就不能再声明 text/plain：
        // 否则对端会按声明发出一条必然被拒的请求，还无法从卡片上看出正确的调用方式。
        assertThat(delegated.inputModes()).containsExactly("text/plain", "application/json");
        assertThat(structuredOnly.inputModes()).containsExactly("application/json");
        assertThat(structuredOnly.outputModes())
                .containsExactly("application/json", "text/plain");
    }

    @Test
    void capabilityFlagsAreOffByDefaultBecauseTheCardIsAPromise() {
        AgentCard card = new AgentCardCodec().encode(PUBLIC_CARD);

        // 声明了却没实现，会让对端建立一个永远无法完成的会话。
        assertThat(card.capabilities().streaming()).isFalse();
        assertThat(card.capabilities().pushNotifications()).isFalse();
        assertThat(card.capabilities().stateTransitionHistory()).isFalse();
    }

    @Test
    void theDefaultProfileDeclaresBearerSecuritySoAnonymousCallersKnowToAuthenticate() {
        AgentCard card = new AgentCardCodec().encode(PUBLIC_CARD);

        assertThat(card.securitySchemes()).containsOnlyKeys("bearer");
        assertThat(card.security()).containsExactly(java.util.Map.of("bearer", List.of()));
    }

    @Test
    void aProfileWithoutSecuritySchemesEmitsNoSecurityDeclarationAtAll() {
        AgentCardCodec.TransportProfile profile = new AgentCardCodec.TransportProfile(
                false, false, false, null, null, null, null);

        AgentCard card = new AgentCardCodec(profile, A2aSelectionMode.DELEGATED_SELECTION)
                .encode(PUBLIC_CARD);

        // security 与 securitySchemes 必须成对出现：只声明其中之一的卡片对对端毫无指导意义。
        assertThat(card.securitySchemes()).isNull();
        assertThat(card.security()).isNull();
    }

    @Test
    void providerAndDocumentationAreOnlyDeclaredWhenConfigured() {
        AgentCardCodec.TransportProfile profile = new AgentCardCodec.TransportProfile(
                false, false, false, "0.3.0", "ACME", "https://docs.internal/a2a",
                AgentCardCodec.TransportProfile.bearerScheme());

        AgentCard configured = new AgentCardCodec(profile, A2aSelectionMode.DELEGATED_SELECTION)
                .encode(PUBLIC_CARD);
        AgentCard bare = new AgentCardCodec().encode(PUBLIC_CARD);

        assertThat(configured.provider().organization()).isEqualTo("ACME");
        assertThat(configured.documentationUrl()).isEqualTo("https://docs.internal/a2a");
        assertThat(configured.protocolVersion()).isEqualTo("0.3.0");
        assertThat(bare.provider()).isNull();
        assertThat(bare.documentationUrl()).isNull();
        // 未配置协议版本时回落到 SDK 默认值，而不是 null。
        assertThat(bare.protocolVersion()).isNotBlank();
    }

    private static AgentCardProjection withSkills(
            AgentCardProjection.SkillProjection... skills) {
        return new AgentCardProjection("capability-gateway", "受治理的企业能力执行平面",
                "https://gateway.internal/a2a", "0.1.0", true,
                List.of("text/plain", "application/json"),
                List.of("text/plain", "application/json"), List.of(skills));
    }

    private static AgentCardProjection.SkillProjection skill(
            String id, String name, String description, List<String> tags,
            List<String> examples) {
        return new AgentCardProjection.SkillProjection(id, name, description, tags, examples);
    }
}
