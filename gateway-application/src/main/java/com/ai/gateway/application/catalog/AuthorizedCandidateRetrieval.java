package com.ai.gateway.application.catalog;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.service.TextNormalizer;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 授权域内候选检索内核：全网关「文本归一化 → BM25 Top-K → 结果落域校验」的<b>唯一</b>实现。
 *
 * <p><b>为什么必须只有一份</b>：这一段的每个步骤都是安全关键的。检索域必须严格等于调用方
 * 已完成授权过滤的子集——一旦某个入口自己写一遍，就等于网关持有多套「检索域从哪来」的判定，
 * 其中任何一处写成「先取全局 Top-K 再求交集」都会让未授权能力参与打分与截断，
 * 而这种越权在功能测试里通常表现为「排序略有不同」，不表现为报错。</p>
 *
 * <p><b>两个入口形状对应两种目录固定方式，而不是两套策略</b>：</p>
 * <ul>
 * <li>{@link #retrieveWithin} —— 运行面自然语言路由与管理面诊断面。检索域由调用方从
 * 已发布快照加授权过滤得出。</li>
 * <li>{@link #retrieveWithinAgentScope} —— Agent 平面（MCP / A2A / Host）。检索域由请求期
 * 已固定的 {@link ActiveCatalogView} 加可见性判定得出，检索走该视图自带的索引句柄，
 * 使「候选来自哪个目录版本」与随后签发的 toolRef / alias 绑定在同一版本上。</li>
 * </ul>
 *
 * <p><b>结果落域校验不是冗余</b>：Lucene 实现已在查询里加了不可绕过的 capKey 过滤子句，
 * 但本内核不假设检索器一定这么实现。任何返回了域外能力的检索器，其结果都在这里被丢弃，
 * 而不是被下游当成合法候选投影出去。</p>
 *
 * <p><b>失败以返回值表达</b>：各入口对同一失败的对外语义并不相同，因此本内核只描述
 * 「发生了什么」（{@link Retrieved.Status}），由调用方决定「对外怎么说」。
 * 新增入口无需修改本类（开闭原则）。</p>
 *
 * <p>线程安全：不持有任何按请求变化的可变状态。</p>
 *
 * @author cmiracle@163.com
 * @see CandidateResolutionService
 * @see AgentCandidateRanker
 * @since 0.2.0
 */
public final class AuthorizedCandidateRetrieval {

    private final CandidateRetriever candidateRetriever;
    private final TextNormalizer textNormalizer;

    /**
     * @param candidateRetriever BM25 检索器；实现 {@link CatalogBoundCandidateRetriever}
     * 时视图面入口会走视图自带索引
     * @param textNormalizer 文本归一化服务
     */
    public AuthorizedCandidateRetrieval(CandidateRetriever candidateRetriever,
                                        TextNormalizer textNormalizer) {
        this.candidateRetriever = Objects.requireNonNull(candidateRetriever,
                "candidateRetriever must not be null");
        this.textNormalizer = Objects.requireNonNull(textNormalizer,
                "textNormalizer must not be null");
    }

    /**
     * 快照面入口：在调用方给出的已授权子集内检索。
     *
     * @param rawText 未归一化文本，允许为 {@code null}（视为空文本）
     * @param scope 已完成授权过滤的检索域，不得为 {@code null}
     * @param topK 候选数上限，必须为正
     * @return 检索结果，永不为 {@code null}
     */
    public Retrieved retrieveWithin(String rawText, List<CapabilityManifest> scope, int topK) {
        Objects.requireNonNull(scope, "scope must not be null");
        return retrieve(rawText, null, scope, topK);
    }

    /**
     * Agent 视图面入口：在请求期已固定的目录视图内检索。
     *
     * <p>{@code WRITE_HIGH} 的排除写在这里而不是各调用点：它是 Agent 投影域的固定规则
     * （高危写操作永不进入任何 Agent 可见的候选集），一旦分散到两个入口各写一次，
     * 漏写的那一次不会有任何报错，只会让一个高危能力静默出现在模型上下文里。
     * 快照面入口没有这条排除——运行面允许经确认通道路由高危写操作，
     * 二者的差异是刻意的，不是遗漏。</p>
     *
     * @param rawText 未归一化文本，允许为 {@code null}
     * @param view 请求期已固定的目录视图，不得为 {@code null}
     * @param visible 可见性判定后的能力集合，不得为 {@code null}
     * @param planeFilter 该承载面自身的附加收窄（如投影存在、schema 预算），
     * 允许为 {@code null} 表示不附加
     * @param topK 召回上限，必须为正
     * @return 检索结果，永不为 {@code null}
     */
    public Retrieved retrieveWithinAgentScope(String rawText,
                                              ActiveCatalogView view,
                                              List<CapabilityManifest> visible,
                                              Predicate<CapabilityManifest> planeFilter,
                                              int topK) {
        Objects.requireNonNull(view, "view must not be null");
        Objects.requireNonNull(visible, "visible must not be null");
        Predicate<CapabilityManifest> plane = planeFilter == null ? manifest -> true : planeFilter;
        List<CapabilityManifest> scope = visible.stream()
                .filter(Objects::nonNull)
                .filter(manifest -> manifest.spec().risk() != RiskLevel.WRITE_HIGH)
                .filter(plane)
                .toList();
        return retrieve(rawText, view, scope, topK);
    }

    /**
     * @return 检索器是否绑定请求期目录视图；为 {@code false} 时调用方需自行判断
     * 「索引版本落后于已发布快照」这类漂移
     */
    public boolean viewBound() {
        return candidateRetriever instanceof CatalogBoundCandidateRetriever;
    }

    /**
     * @return 检索器当前已建索引的目录版本；检索器未实现时为其默认值
     */
    public long indexedCatalogVersion() {
        return candidateRetriever.indexedCatalogVersion();
    }

    private Retrieved retrieve(String rawText,
                               ActiveCatalogView view,
                               List<CapabilityManifest> scope,
                               int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        if (scope.isEmpty()) {
            return new Retrieved(Retrieved.Status.EMPTY_SCOPE, "", 0, List.of(), null);
        }
        String normalizedText = textNormalizer.normalize(rawText == null ? "" : rawText);
        if (normalizedText.isBlank()) {
            return new Retrieved(Retrieved.Status.BLANK_TEXT, "", scope.size(), List.of(), null);
        }
        List<CandidateRetriever.ScoredCapability> retrieved;
        try {
            retrieved = view != null
                    && candidateRetriever instanceof CatalogBoundCandidateRetriever bound
                    ? bound.retrieve(normalizedText, view, scope, topK)
                    : candidateRetriever.retrieve(normalizedText, scope, topK);
        } catch (RuntimeException e) {
            return new Retrieved(Retrieved.Status.RETRIEVAL_FAILED, normalizedText,
                    scope.size(), List.of(), e.getMessage());
        }
        return new Retrieved(Retrieved.Status.RETRIEVED, normalizedText, scope.size(),
                withinScope(retrieved, scope), null);
    }

    /** 丢弃任何不在检索域内的返回项，使「检索域 == 已授权子集」成为出口处的硬保证。 */
    private static List<CandidateRetriever.ScoredCapability> withinScope(
            List<CandidateRetriever.ScoredCapability> retrieved,
            List<CapabilityManifest> scope) {
        if (retrieved == null || retrieved.isEmpty()) {
            return List.of();
        }
        Set<String> scopeKeys = scope.stream()
                .map(AuthorizedCandidateRetrieval::keyOf)
                .collect(Collectors.toUnmodifiableSet());
        return retrieved.stream()
                .filter(Objects::nonNull)
                .filter(scored -> scored.capability() != null)
                .filter(scored -> scopeKeys.contains(keyOf(scored.capability())))
                .toList();
    }

    private static String keyOf(CapabilityManifest manifest) {
        return manifest.metadata().id() + '\n' + manifest.metadata().version();
    }

    /**
     * 检索结果。
     *
     * @param status 结果分类
     * @param normalizedText 归一化后的检索词；未进行到该步时为空串
     * @param scopeSize 收窄后的检索域大小，用于诊断归因
     * @param candidates 按检索器打分降序的候选列表，永不为 {@code null}，且必然落在检索域内
     * @param diagnosticReason 内部归因文本（如检索器异常消息），仅可用于日志与管理面诊断，
     * <b>不得</b>直接透出到运行面对外响应；无归因时为 {@code null}
     */
    public record Retrieved(Status status,
                            String normalizedText,
                            int scopeSize,
                            List<CandidateRetriever.ScoredCapability> candidates,
                            String diagnosticReason) {

        public Retrieved {
            Objects.requireNonNull(status, "status must not be null");
            normalizedText = normalizedText == null ? "" : normalizedText;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /** @return 是否完成了检索；候选列表仍可能为空（无命中） */
        public boolean retrieved() {
            return status == Status.RETRIEVED;
        }

        /** 检索结果分类；不承载任何对外错误码或 HTTP 语义。 */
        public enum Status {
            /** 检索已执行；候选列表可能为空。 */
            RETRIEVED,
            /** 收窄后的检索域为空，未发起检索。 */
            EMPTY_SCOPE,
            /** 文本归一化后为空（如全部为停用词），未发起检索。 */
            BLANK_TEXT,
            /** 检索引擎调用失败。 */
            RETRIEVAL_FAILED
        }
    }
}
