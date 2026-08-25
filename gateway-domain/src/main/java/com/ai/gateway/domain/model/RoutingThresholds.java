package com.ai.gateway.domain.model;

/**
 * 自然语言路由流水线中候选检索与歧义消解的阈值。
 *
 * <p>规定路由不得依赖模型自报的置信度。网关改用离线标注的基准来确定以下阈值：</p>
 *
 * <ul>
 * <li>{@code minRelevanceScore} - 候选被纳入 Top-K 集合所需的最低 BM25 相关度分数。</li>
 * <li>{@code minTop1Top2ScoreDiff} - top-1 与 top-2 候选之间的最小分差；分差越小越
 * 表示存在歧义。</li>
 * <li>{@code maxCandidates} - 传入 LLM 进行选择（Top-K）的最大候选数。</li>
 * <li>{@code maxTokenBudget} - 包含候选描述与入参 Schema 的 LLM 提示词的最大 token 预算。</li>
 * </ul>
 *
 * <p>当发生以下任一情况时，网关必须请求澄清或返回 {@code NO_MATCH}：</p>
 * <ul>
 * <li>检索分数低于阈值。</li>
 * <li>多个候选表达了不同的业务动作，且无法从请求中区分。</li>
 * <li>必填参数缺失。</li>
 * <li>用户同时表达了多个动作。</li>
 * <li>用户请求的动作风险超出当前入口点允许的等级。</li>
 * <li>模型在候选集之外选择、输出不可解析结果，或多次重试结果不一致。</li>
 * </ul>
 *
 * @param minRelevanceScore 候选纳入的最低 BM25 分数
 * @param minTop1Top2ScoreDiff top-1/top-2 最小分差
 * @param maxCandidates 传入 LLM 的最大 Top-K 候选数
 * @param maxTokenBudget LLM 提示词的最大 token 预算
 * @since 0.1.0
 */
public record RoutingThresholds(
        double minRelevanceScore,
        double minTop1Top2ScoreDiff,
        int maxCandidates,
        int maxTokenBudget
) {

    /**
     * 返回当前版本的默认路由阈值。
     *
     * <p>运行面自然语言路由与管理面能力目录诊断<b>必须</b>共用这一份取值：诊断的唯一
     * 价值在于复现运行面的真实判定，若两侧各自持有阈值常量，诊断结论就会与线上行为
     * 分叉，进而给出错误的清单修复建议。新增入口同样应从此处取值，而不是自行拷贝数字。</p>
     *
     * @return 默认阈值；取值来源为离线标注基准，不依赖模型自报置信度
     */
    public static RoutingThresholds defaults() {
        return new RoutingThresholds(
                1.0,  // minRelevanceScore：BM25 最低相关度
                0.5,  // minTop1Top2ScoreDiff：低于该分差视为歧义
                5,    // maxCandidates：Top-K
                4096  // maxTokenBudget
        );
    }
}
