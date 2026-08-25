package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * 表示 LLM 在自然语言路由期间可能返回的三种决策的密封接口（sealed interface）。
 *
 * <p>规定模型必须恰好返回三种决策类型之一。网关使用短别名（如
 * {@code cap_7k3m2v6p4a9d1f8q}）而非原始能力 ID，以避免函数名冲突、点/冒号问题及长度
 * 限制。别名到能力的映射按请求维护：</p>
 *
 * <pre>
 * alias -&gt; capabilityId + capabilityVersion + manifestDigest
 * </pre>
 *
 * <p>模型返回后，网关执行确定性检查：</p>
 * <ol>
 * <li>所选别名属于本请求的候选集。</li>
 * <li>当前 Principal 仍有相应权限。</li>
 * <li>能力版本仍可执行。</li>
 * <li>参数满足 JSON Schema 与业务约束。</li>
 * <li>所有非模型参数均从受信任来源注入。</li>
 * <li>风险等级允许当前执行模式。</li>
 * </ol>
 *
 * <p>模型不得声明鉴权成功或执行成功。模型生成的自由文本原因仅用于 UX，不作为控制流
 * 决策。</p>
 *
 * @since 0.1.0
 */
public sealed interface ModelDecision
        permits ModelDecision.SelectDecision,
                ModelDecision.ClarifyDecision,
                ModelDecision.NoMatchDecision {

    /**
     * 模型已选定某个能力别名并提供了 MODEL 参数。
     *
     * <p>网关在继续之前必须校验该别名属于当前请求的候选集。参数将针对能力的公开入参
     * Schema 进行校验。</p>
     *
     * @param alias 标识所选能力的短别名
     * @param arguments 模型生成的参数（JSON 兼容 map 形式）
     */
    record SelectDecision(String alias, Map<String, Object> arguments)
            implements ModelDecision {

        /**
         * 紧凑构造器，执行防御性拷贝。
         *
         * @param alias 能力别名
         * @param arguments 模型参数
         */
        public SelectDecision {
            java.util.Objects.requireNonNull(alias, "alias must not be null");
            java.util.Objects.requireNonNull(arguments, "arguments must not be null");
            arguments = Map.copyOf(arguments);
        }
    }

    /**
     * 模型向用户请求澄清。
     *
     * <p>定义澄清会话：网关存储候选能力集、已确认的非敏感参数与待填字段。后续回答只能
     * 补充缺失信息或在原候选集内消歧。若用户偏离了原始意图，网关必须检测意图跳转并
     * 重启完整的路由流水线。</p>
     *
     * @param question 向用户展示的澄清问题
     */
    record ClarifyDecision(String question)
            implements ModelDecision {

        /**
         * 紧凑构造器，执行 null 检查。
         *
         * @param question 澄清问题
         */
        public ClarifyDecision {
            java.util.Objects.requireNonNull(question, "question must not be null");
        }
    }

    /**
     * 模型判定候选集中没有任何能力匹配用户请求。
     *
     * <p>网关向调用方返回 {@link ErrorCode#NO_CAPABILITY_MATCH}。原因码仅用于 UX/日志，
     * 不作为控制流决策。</p>
     *
     * @param reasonCode 稳定的原因码（如 {@code "NO_SUPPORTED_CAPABILITY"}）
     */
    record NoMatchDecision(String reasonCode)
            implements ModelDecision {

        /**
         * 紧凑构造器，执行 null 检查。
         *
         * @param reasonCode 原因码
         */
        public NoMatchDecision {
            java.util.Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        }
    }
}
