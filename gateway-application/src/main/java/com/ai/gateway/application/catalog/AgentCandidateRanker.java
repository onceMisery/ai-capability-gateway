package com.ai.gateway.application.catalog;

import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.service.TextNormalizer;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Agent 平面候选重排规则：全网关<b>唯一</b>一份「BM25 分数 → 最终展示顺序」的换算。
 *
 * <p><b>为什么必须只有一份</b>：Agent 平面有两个候选入口（工具目录投影与 Host 侧
 * capability resolve）。两者若各自写一遍加分项与并列打破规则，同一个 Principal、
 * 同一句查询就会在两个入口得到不同的 Top-1——而 Top-1 决定了模型看到哪份 schema、
 * 决定了 toolRef 签给哪个能力。这类偏移不会报错，只会让「网关按什么顺序推荐能力」
 * 变成一件依赖入口的事，从而无法用一次诊断解释线上行为。</p>
 *
 * <p><b>并列打破必须是全序</b>：分数相同时按能力 id、再按版本升序。缺了这一层，
 * 同分候选的相对顺序就取决于检索器返回顺序，跨实例与跨重建索引都可能变化。</p>
 *
 * <p>本类不调用任何模型：加分项全部来自清单文本与风险档，是确定性算法。</p>
 *
 * <p>线程安全：不持有任何按请求变化的可变状态。</p>
 *
 * @author cmiracle@163.com
 * @see AuthorizedCandidateRetrieval
 * @since 0.2.0
 */
public final class AgentCandidateRanker {

    /** 查询与展示名完全一致：这是用户/模型指名要某个能力，应压倒词法相关度。 */
    public static final double EXACT_NAME_BONUS = 10.0d;

    /** 查询包含展示名：弱于指名，但强于纯词法命中。 */
    public static final double PARTIAL_NAME_BONUS = 3.0d;

    /** 只读能力的微小优先：同分时优先给出无副作用的那一个。 */
    public static final double READ_ONLY_BONUS = 0.05d;

    /** 复杂 schema 的微小惩罚：同分时优先给出模型更可能一次填对参数的那一个。 */
    public static final double COMPLEX_SCHEMA_PENALTY = 0.05d;

    /** 全序比较器：分数降序 → 能力 id 升序 → 版本升序。 */
    private static final Comparator<Ranked> ORDER =
            Comparator.comparingDouble(Ranked::rankScore).reversed()
                    .thenComparing(ranked -> ranked.capability().metadata().id())
                    .thenComparing(ranked -> ranked.capability().metadata().version());

    private final TextNormalizer textNormalizer;

    /**
     * @param textNormalizer 文本归一化服务；展示名与查询必须用同一套归一化规则比较，
     * 否则「完全一致」这一档会因全半角或大小写差异而随机失效
     */
    public AgentCandidateRanker(TextNormalizer textNormalizer) {
        this.textNormalizer = Objects.requireNonNull(textNormalizer,
                "textNormalizer must not be null");
    }

    /**
     * 对已落域的检索结果重排。
     *
     * <p>取不到公开投影的候选被丢弃而不是降级展示：投影是 Agent 平面唯一允许对外的形态，
     * 一个投影缺失的能力没有可展示的字段。正常情况下不会发生——{@link ActiveCatalogView}
     * 在构造期就要求每个能力都能通过投影治理，因此这里是兜底而非主路径。</p>
     *
     * @param normalizedText 已归一化的查询文本，允许为 {@code null}（视为空文本）
     * @param view 请求期已固定的目录视图，不得为 {@code null}
     * @param retrieved 已落域的检索结果，允许为 {@code null}
     * @return 按最终展示顺序排列的候选，永不为 {@code null}
     */
    public List<Ranked> rank(String normalizedText,
                             ActiveCatalogView view,
                             List<CandidateRetriever.ScoredCapability> retrieved) {
        Objects.requireNonNull(view, "view must not be null");
        if (retrieved == null || retrieved.isEmpty()) {
            return List.of();
        }
        String query = normalizedText == null ? "" : normalizedText;
        return retrieved.stream()
                .filter(Objects::nonNull)
                .filter(scored -> scored.capability() != null)
                .map(scored -> view.publicProjection(scored.capability())
                        .map(projection -> rank(query, scored, projection))
                        .orElse(null))
                .filter(Objects::nonNull)
                .sorted(ORDER)
                .toList();
    }

    private Ranked rank(String normalizedText,
                        CandidateRetriever.ScoredCapability scored,
                        CapabilityPublicProjectionService.Projection projection) {
        String normalizedName = textNormalizer.normalize(projection.displayName());
        boolean exactNameMatch = !normalizedName.isEmpty()
                && normalizedText.equals(normalizedName);
        double rankScore = scored.score();
        if (exactNameMatch) {
            rankScore += EXACT_NAME_BONUS;
        } else if (!normalizedName.isEmpty() && normalizedText.contains(normalizedName)) {
            rankScore += PARTIAL_NAME_BONUS;
        }
        if (scored.capability().spec().risk() == RiskLevel.READ_ONLY) {
            rankScore += READ_ONLY_BONUS;
        }
        if (projection.schemaClass() == CapabilityPublicProjectionService.SchemaClass.COMPLEX) {
            rankScore -= COMPLEX_SCHEMA_PENALTY;
        }
        return new Ranked(scored.capability(), projection, scored.score(),
                rankScore, exactNameMatch);
    }

    /**
     * 一个已重排的候选。
     *
     * @param capability 能力清单（Host 侧数据，不得进入模型上下文）
     * @param projection 公开投影
     * @param retrievalScore 检索器给出的原始 BM25 分数
     * @param rankScore 重排后的展示分数
     * @param exactNameMatch 查询是否与展示名完全一致；调用方判定「高置信直取」时复用该结论，
     * 以免第三处再写一遍同一个比较
     */
    public record Ranked(CapabilityManifest capability,
                         CapabilityPublicProjectionService.Projection projection,
                         double retrievalScore,
                         double rankScore,
                         boolean exactNameMatch) {

        public Ranked {
            Objects.requireNonNull(capability, "capability must not be null");
            Objects.requireNonNull(projection, "projection must not be null");
        }
    }
}
