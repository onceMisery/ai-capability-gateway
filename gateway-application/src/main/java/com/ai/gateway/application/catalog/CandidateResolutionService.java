package com.ai.gateway.application.catalog;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.service.TextNormalizer;

import java.util.List;
import java.util.Objects;

/**
 * 候选能力确定性解析服务：把「快照固定 → 授权前置过滤 → 文本归一化 → BM25 Top-K」
 * 这一段收敛为唯一实现。
 *
 * <p><b>为什么必须只有一份</b>：该链路包含授权前置过滤这一安全关键步骤。若运行面
 * 自然语言路由、Agent 平面、诊断面各自持有一套检索入口，就等于持有多套授权过滤，
 * 任何一处遗漏都是越权泄漏，而不仅是重复代码。因此本服务是所有入口的共用内核。</p>
 *
 * <p><b>本服务不调用任何模型</b>：BM25 检索与文本归一化都是确定性算法。
 * “消费自然语言”与“自己跑 LLM 编排”是两件事，本服务只承担前者，因此不受运行面
 * LLM 编排冻结线的约束，也不受 {@code gateway.runtime.nl-router.mode} 影响。</p>
 *
 * <p><b>失败以返回值表达，不以异常表达</b>：各入口对同一失败的对外错误码与 HTTP
 * 语义并不相同（运行面返回稳定错误码、诊断面需要归因文本），因此本服务只描述
 * “发生了什么”（{@link Outcome}），由调用方决定“对外怎么说”。新增入口时无需
 * 修改本服务（开闭原则）。</p>
 *
 * <p>线程安全：不持有任何按请求变化的可变状态。</p>
 *
 * @see Outcome
 * @see Resolution
 * @since 0.2.0
 */
public final class CandidateResolutionService {

    private final CatalogPort catalogPort;
    private final AuthorizationPort authorizationPort;
    private final CandidateRetriever candidateRetriever;
    private final TextNormalizer textNormalizer;
    private final String environment;

    /**
     * @param catalogPort 已发布目录快照来源
     * @param authorizationPort 可见性授权过滤
     * @param candidateRetriever BM25 检索器
     * @param textNormalizer 文本归一化服务
     * @param environment 运行环境标识，用于选取对应环境的已发布快照
     */
    public CandidateResolutionService(CatalogPort catalogPort,
                                      AuthorizationPort authorizationPort,
                                      CandidateRetriever candidateRetriever,
                                      TextNormalizer textNormalizer,
                                      String environment) {
        this.catalogPort = Objects.requireNonNull(catalogPort, "catalogPort must not be null");
        this.authorizationPort = Objects.requireNonNull(authorizationPort,
                "authorizationPort must not be null");
        this.candidateRetriever = Objects.requireNonNull(candidateRetriever,
                "candidateRetriever must not be null");
        this.textNormalizer = Objects.requireNonNull(textNormalizer,
                "textNormalizer must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    /**
     * 对给定主体与自然语言文本执行确定性候选解析。
     *
     * <p>步骤顺序是契约的一部分：授权过滤先于文本归一化，使「主体无可见能力」
     * 与「文本归一化后为空」两类失败的优先级稳定，便于各入口给出一致的错误语义。</p>
     *
     * @param principal 已认证主体，不可为 {@code null}
     * @param rawText 未归一化的自然语言文本，允许为 {@code null}（视为空文本）
     * @param topK 候选数上限，必须为正
     * @return 解析结果；{@link Outcome#RESOLVED} 时 {@code candidates} 可能为空列表
     * （检索无命中），由调用方按自身阈值策略处理
     * @throws NullPointerException {@code principal} 为 {@code null} 时抛出
     * @throws IllegalArgumentException {@code topK} 非正时抛出
     */
    public Resolution resolve(Principal principal, String rawText, int topK) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }

        // 第 1 步：固定目录快照。运行期只读不可变的已发布快照。
        CatalogSnapshot snapshot;
        try {
            snapshot = catalogPort.loadCurrentSnapshot(environment);
        } catch (RuntimeException e) {
            return Resolution.failed(Outcome.SNAPSHOT_UNAVAILABLE, 0L, e.getMessage());
        }
        if (snapshot == null || snapshot.capabilities().isEmpty()) {
            return Resolution.failed(Outcome.EMPTY_CATALOG, 0L, "no published capability");
        }
        long snapshotVersion = snapshot.snapshotVersion();

        // 第 2 步：授权前置过滤。未授权能力不得参与打分与截断，更不得泄漏其存在。
        List<CapabilityManifest> visible;
        try {
            visible = authorizationPort.filterVisibleCapabilities(
                    principal, snapshot.capabilities());
        } catch (RuntimeException e) {
            return Resolution.failed(Outcome.AUTHORIZATION_UNAVAILABLE, snapshotVersion,
                    e.getMessage());
        }
        if (visible == null || visible.isEmpty()) {
            return Resolution.failed(Outcome.NO_VISIBLE_CAPABILITY, snapshotVersion,
                    "principal has no visible capability");
        }

        // 第 3 步：文本归一化（停用词、全半角、大小写、标点）。
        String normalizedText = textNormalizer.normalize(rawText == null ? "" : rawText);
        if (normalizedText.isBlank()) {
            return new Resolution(Outcome.EMPTY_NORMALIZED_TEXT, snapshotVersion, "",
                    visible.size(), List.of(), "normalized text is blank");
        }

        // 第 4 步：BM25 Top-K 检索，检索域严格限定为已授权子集。
        List<CandidateRetriever.ScoredCapability> candidates;
        try {
            candidates = candidateRetriever.retrieve(normalizedText, visible, topK);
        } catch (RuntimeException e) {
            return new Resolution(Outcome.RETRIEVAL_FAILED, snapshotVersion, normalizedText,
                    visible.size(), List.of(), e.getMessage());
        }
        return new Resolution(Outcome.RESOLVED, snapshotVersion, normalizedText,
                visible.size(), candidates == null ? List.of() : List.copyOf(candidates), null);
    }

    /**
     * 返回检索器当前已建索引的目录版本，用于诊断「索引落后于已发布快照」这类漂移。
     *
     * <p>运行面不需要该信息（检索域已由快照固定），因此它不进入 {@link Resolution}；
     * 仅管理面诊断按需读取，避免为诊断需求污染运行面返回值。</p>
     *
     * @return 已建索引的目录版本；检索器未实现时为其默认值
     */
    public long indexedCatalogVersion() {
        return candidateRetriever.indexedCatalogVersion();
    }

    /** 解析结果分类。调用方据此映射自身的对外错误码，本枚举不承载 HTTP 语义。 */
    public enum Outcome {
        /** 解析成功；候选列表可能为空。 */
        RESOLVED,
        /** 目录快照加载失败（数据源不可用）。 */
        SNAPSHOT_UNAVAILABLE,
        /** 环境内没有任何已发布能力。 */
        EMPTY_CATALOG,
        /** 授权数据源不可用，按 fail-closed 处理。 */
        AUTHORIZATION_UNAVAILABLE,
        /** 该主体没有任何可见能力。对外表述不得区别于「无匹配」，以免泄漏能力存在性。 */
        NO_VISIBLE_CAPABILITY,
        /** 文本归一化后为空（如全部为停用词）。 */
        EMPTY_NORMALIZED_TEXT,
        /** 检索引擎调用失败。 */
        RETRIEVAL_FAILED;

        /** @return 是否为成功解析 */
        public boolean resolved() {
            return this == RESOLVED;
        }
    }

    /**
     * 解析结果。
     *
     * @param outcome 结果分类
     * @param snapshotVersion 已固定的快照版本；快照不可用时为 {@code 0}
     * @param normalizedText 归一化后的检索词；未进行到该步时为空串
     * @param visibleCapabilityCount 授权过滤后的可见能力总数，用于诊断归因
     * @param candidates 按分数降序的候选列表，永不为 {@code null}
     * @param diagnosticReason 内部归因文本，仅可用于日志与管理面诊断，
     * <b>不得</b>直接透出到运行面对外响应
     */
    public record Resolution(Outcome outcome,
                             long snapshotVersion,
                             String normalizedText,
                             int visibleCapabilityCount,
                             List<CandidateRetriever.ScoredCapability> candidates,
                             String diagnosticReason) {

        public Resolution {
            Objects.requireNonNull(outcome, "outcome must not be null");
            normalizedText = normalizedText == null ? "" : normalizedText;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        private static Resolution failed(Outcome outcome, long snapshotVersion, String reason) {
            return new Resolution(outcome, snapshotVersion, "", 0, List.of(), reason);
        }

        /** @return 是否解析成功 */
        public boolean resolved() {
            return outcome.resolved();
        }
    }
}
