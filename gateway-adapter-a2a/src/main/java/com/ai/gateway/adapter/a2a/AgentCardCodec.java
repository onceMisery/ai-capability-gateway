package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentCardProjection;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.HTTPAuthSecurityScheme;
import io.a2a.spec.SecurityScheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把中立投影 {@link AgentCardProjection} 编码为 A2A SDK 的 {@link AgentCard}。
 *
 * <p>本类是 SDK 类型出现的<b>唯一</b>位置边界之一：application 层只产出投影记录，
 * 由适配层完成 SDK 编码。这样做的直接收益有两条——依赖方向不被打破
 * （{@code ArchitectureTest} 与 {@code ApplicationArchitectureTest} 禁止上层出现 {@code io.a2a..}），
 * 以及 SDK 升级只会波及本类而不会波及投影规则。</p>
 *
 * <p><b>本类不做任何投影决策。</b>它不会新增、删减或改写任何 skill，也不会根据身份做判断：
 * 「哪些业务域对该 peer 可见」已经在 {@code AgentCardProjectionService} 里决定完毕。
 * 本类只负责两件投影层不该关心的事：</p>
 * <ol>
 * <li>填充<b>传输属性</b>（{@code streaming} / {@code pushNotifications} / {@code preferredTransport}）
 * 与<b>安全方案</b>（{@code securitySchemes} / {@code security}）——它们是部署属性，
 * 与可见面裁剪正交；</li>
 * <li>按 {@link A2aSelectionMode} 声明每个 skill 的入参与出参媒体类型，
 * 使卡片上的声明与适配器实际受理的形态严格一致。</li>
 * </ol>
 *
 * <p>本类不可变且线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class AgentCardCodec {

    /** A2A 的安全方案名：卡片上只声明「需要 Bearer」，不声明任何具体凭据来源。 */
    private static final String SECURITY_SCHEME_NAME = "bearer";

    private final TransportProfile transportProfile;
    private final A2aSelectionMode selectionMode;

    /**
     * 使用默认传输属性与默认选择模式构造编码器。
     */
    public AgentCardCodec() {
        this(TransportProfile.defaults(), A2aSelectionMode.DELEGATED_SELECTION);
    }

    /**
     * @param transportProfile 传输与安全属性，不能为 {@code null}
     * @param selectionMode    选择模式，决定 skill 上声明的入参媒体类型，不能为 {@code null}
     */
    public AgentCardCodec(TransportProfile transportProfile, A2aSelectionMode selectionMode) {
        this.transportProfile = Objects.requireNonNull(
                transportProfile, "transportProfile must not be null");
        this.selectionMode = Objects.requireNonNull(
                selectionMode, "selectionMode must not be null");
    }

    /**
     * 编码为 SDK 的 {@link AgentCard}。
     *
     * <p>{@code skills} 逐条按序编码，空投影编码为空列表而不是 {@code null}：
     * SDK 的紧凑构造器对 {@code skills} 有非空断言，而公开卡的 skills 恒为空列表，
     * 这两点必须同时成立。</p>
     *
     * @param projection 中立投影，不能为 {@code null}
     * @return SDK 卡片
     */
    public AgentCard encode(AgentCardProjection projection) {
        Objects.requireNonNull(projection, "projection must not be null");
        List<AgentSkill> skills = new ArrayList<>(projection.skills().size());
        for (AgentCardProjection.SkillProjection skill : projection.skills()) {
            skills.add(encodeSkill(skill));
        }
        AgentCard.Builder builder = new AgentCard.Builder()
                .name(projection.agentName())
                .description(projection.description())
                .url(projection.url())
                .version(projection.version())
                .protocolVersion(transportProfile.protocolVersion())
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(transportProfile.streaming())
                        .pushNotifications(transportProfile.pushNotifications())
                        .stateTransitionHistory(transportProfile.stateTransitionHistory())
                        .build())
                .defaultInputModes(projection.defaultInputModes())
                .defaultOutputModes(projection.defaultOutputModes())
                .skills(List.copyOf(skills))
                .supportsAuthenticatedExtendedCard(
                        projection.supportsAuthenticatedExtendedCard());
        if (transportProfile.organization() != null) {
            builder.provider(new AgentProvider(transportProfile.organization(),
                    projection.url()));
        }
        if (transportProfile.documentationUrl() != null) {
            builder.documentationUrl(transportProfile.documentationUrl());
        }
        if (transportProfile.securitySchemes() != null) {
            builder.securitySchemes(transportProfile.securitySchemes())
                    // security 声明「调用扩展卡与 Task 都必须携带 Bearer」，作用域列表为空表示不细分。
                    .security(List.of(Map.of(SECURITY_SCHEME_NAME, List.of())));
        }
        return builder.build();
    }

    /**
     * 编码单个业务域技能。
     *
     * <p>{@code examples} 为空时传 {@code null} 而不是空列表：SDK 的
     * {@code @JsonInclude(NON_ABSENT)} 会省略 {@code null} 字段，
     * 而一个空数组在卡片上会被对端读成「该域没有任何可用示例」这类多余信息。</p>
     */
    private AgentSkill encodeSkill(AgentCardProjection.SkillProjection skill) {
        return new AgentSkill.Builder()
                .id(skill.id())
                .name(skill.name())
                .description(skill.description())
                .tags(skill.tags())
                .examples(skill.examples().isEmpty() ? null : skill.examples())
                .inputModes(selectionMode.inputModes())
                .outputModes(selectionMode.outputModes())
                .build();
    }

    /**
     * 传输与安全属性。
     *
     * <p>刻意与投影分离：这些字段由部署配置决定，若混入投影记录，
     * 投影测试就不得不断言与「可见面裁剪」毫无关系的传输字段。</p>
     *
     * @param streaming              是否支持流式（当前实现为一次性 Task，故默认关闭）
     * @param pushNotifications      是否支持推送通知
     * @param stateTransitionHistory 是否在 Task 上返回状态迁移历史
     * @param protocolVersion        A2A 协议版本，{@code null} 表示使用 SDK 默认值
     * @param organization           提供方组织名，{@code null} 表示不声明 provider
     * @param documentationUrl       文档地址，允许为 {@code null}
     * @param securitySchemes        安全方案映射，{@code null} 表示不在卡片上声明安全要求
     */
    public record TransportProfile(boolean streaming,
                                   boolean pushNotifications,
                                   boolean stateTransitionHistory,
                                   String protocolVersion,
                                   String organization,
                                   String documentationUrl,
                                   Map<String, SecurityScheme> securitySchemes) {

        /**
         * 紧凑构造器，对安全方案映射做防御性拷贝。
         *
         * @param streaming              是否支持流式
         * @param pushNotifications      是否支持推送通知
         * @param stateTransitionHistory 是否返回状态迁移历史
         * @param protocolVersion        协议版本
         * @param organization           组织名
         * @param documentationUrl       文档地址
         * @param securitySchemes        安全方案映射
         */
        public TransportProfile {
            securitySchemes = securitySchemes == null ? null : Map.copyOf(securitySchemes);
        }

        /**
         * 返回默认传输属性：不支持流式与推送，不返回迁移历史，声明 Bearer 安全方案。
         *
         * <p>三个能力位默认全关是有意的：AgentCard 上的能力声明是<b>承诺</b>，
         * 声明了却没实现会让对端建立无法完成的会话。</p>
         *
         * @return 默认传输属性
         */
        public static TransportProfile defaults() {
            return new TransportProfile(false, false, false, null,
                    null, null, bearerScheme());
        }

        /**
         * 构造仅含 Bearer 的安全方案映射。
         *
         * @return 安全方案映射
         */
        public static Map<String, SecurityScheme> bearerScheme() {
            return Map.of(SECURITY_SCHEME_NAME,
                    new HTTPAuthSecurityScheme.Builder()
                            .scheme("bearer")
                            .description("Gateway-issued bearer credential")
                            .build());
        }
    }
}
