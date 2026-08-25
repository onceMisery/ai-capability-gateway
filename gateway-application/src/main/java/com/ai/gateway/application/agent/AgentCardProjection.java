package com.ai.gateway.application.agent;

import java.util.List;
import java.util.Objects;

/**
 * 与 A2A SDK 无关的 AgentCard 中立投影。
 *
 * <p>本记录刻意<b>不</b>引用 {@code io.a2a.spec.AgentCard}：application 层一旦直接产出 SDK 类型，
 * 依赖方向就被打破（{@code ArchitectureTest} 会失败），且 SDK 升级会波及投影规则本身。
 * SDK 记录的构造由适配层的 {@code AgentCardCodec} 完成，本记录只承载「投影决定了什么」。</p>
 *
 * <p>传输能力（streaming、pushNotifications）与安全方案（securitySchemes、security）
 * 有意不在此出现：它们是部署与传输属性，由适配层按配置填充，与「哪些业务域对该 peer 可见」
 * 是两个正交决策。混在一起会让投影测试不得不断言与安全无关的传输字段。</p>
 *
 * @param agentName                          网关自身的 Agent 名称
 * @param description                        网关自身的用途描述
 * @param url                                A2A 服务端点地址
 * @param version                            网关版本
 * @param supportsAuthenticatedExtendedCard  是否支持 {@code agent/getAuthenticatedExtendedCard}
 * @param defaultInputModes                  默认入参媒体类型
 * @param defaultOutputModes                 默认出参媒体类型
 * @param skills                             业务域粒度的技能列表；公开卡恒为空列表
 * @since 0.1.0
 */
public record AgentCardProjection(
        String agentName,
        String description,
        String url,
        String version,
        boolean supportsAuthenticatedExtendedCard,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<SkillProjection> skills
) {

    /**
     * 紧凑构造器，执行防御性拷贝。
     *
     * @param agentName                         Agent 名称
     * @param description                       用途描述
     * @param url                               服务端点
     * @param version                           版本
     * @param supportsAuthenticatedExtendedCard 是否支持扩展卡
     * @param defaultInputModes                 入参媒体类型
     * @param defaultOutputModes                出参媒体类型
     * @param skills                            技能列表
     */
    public AgentCardProjection {
        Objects.requireNonNull(agentName, "agentName must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(version, "version must not be null");
        defaultInputModes = defaultInputModes == null
                ? List.of() : List.copyOf(defaultInputModes);
        defaultOutputModes = defaultOutputModes == null
                ? List.of() : List.copyOf(defaultOutputModes);
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /**
     * 业务域粒度的技能投影。
     *
     * <p><b>粒度是业务域，不是能力。</b>单个 Capability 永不单独成为一个 skill：
     * 那会把真实 {@code capabilityId} 与能力目录结构一并公开，同时违反「未授权能力名不得泄漏」
     * 与「模型不得看到真实 capabilityId」两条既有约束。</p>
     *
     * @param id          {@code domain.<域>} 形态的稳定标识
     * @param name        域的展示名
     * @param description 域的用途描述，来自已治理的公开投影
     * @param tags        固定词表标签：{@code read-only} / {@code requires-confirmation}
     * @param examples    脱敏后的自然语言示例
     */
    public record SkillProjection(
            String id,
            String name,
            String description,
            List<String> tags,
            List<String> examples
    ) {

        /**
         * 紧凑构造器，执行防御性拷贝。
         *
         * @param id          技能标识
         * @param name        展示名
         * @param description 描述
         * @param tags        标签
         * @param examples    示例
         */
        public SkillProjection {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }
}
