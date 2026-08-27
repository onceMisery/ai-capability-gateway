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
 * 自然语言路由与诊断面各自持有一套检索入口，就等于持有多套授权过滤，
 * 任何一处遗漏都是越权泄漏，而不仅是重复代码。</p>
 *
 * <p><b>与 Agent 平面的关系</b>：Agent 平面（MCP / A2A / Host）不经过本服务，因为它的目录
 * 固定方式不同——它必须在请求期锁定 {@link ActiveCatalogView}，随后签发的 toolRef / alias
 * 都绑定在该视图的目录版本上；若改为经本服务重新加载一次快照，要么多读一次目录，
 * 要么让候选与绑定落在两个版本上。二者共享的是更下一层的
 * {@link AuthorizedCandidateRetrieval}：「归一化 → BM25 → 结果落域校验」全网关只有一份实现，
 * 因此两个平面对同一 Principal、同一 query 得到同一候选集合与同一排序。</p>
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
 * @see AuthorizedCandidateRetrieval
 * @since 0.2.0
 */
public final class CandidateResolutionService {

    private final CatalogPort catalogPort;
    private final AuthorizationPort authorizationPort;
    private final AuthorizedCandidateRetrieval retrieval;
    private final String environment;

    /**
     * @param catalogPort 已发布目录快照来源
     * @param authorizationPort 可见性授权过滤
     * @param retrieval 授权域内检索内核，与 Agent 平面共用同一实例
     * @param environment 运行环境标识，用于选取对应环境的已发布快照
     */
    public CandidateResolutionService(CatalogPort catalogPort,
                                      AuthorizationPort authorizationPort,
                                      AuthorizedCandidateRetrieval retrieval,
                                      String environment) {
        this.catalogPort = Objects.requireNonNull(catalogPort, "catalogPort must not be null");
        this.authorizationPort = Objects.requireNonNull(authorizationPort,
                "authorizationPort must not be null");
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    /**
     * 便捷构造：由检索器与归一化服务自行组装检索内核。
     *
     * <p>仅供只使用快照面入口的调用方（运行面 NL、管理面诊断）使用。Agent 平面必须与本服务
     * <b>共用同一个</b> {@link AuthorizedCandidateRetrieval} 实例，因此走上面那个构造器。</p>
     *
     * @param catalogPort 已发布目录快照来源
     * @param authorizationPort 可见性授权过滤
     * @param candidateRetriever BM25 检索器
     * @param textNormalizer 文本归一化服务
     * @param environment 运行环境标识
     */
    public CandidateResolutionService(CatalogPort catalogPort,
                                      AuthorizationPort authorizationPort,
                                      CandidateRetriever candidateRetriever,
                                      TextNormalizer textNormalizer,
                                      String environment) {
        this(catalogPort, authorizationPort,
                new AuthorizedCandidateRetrieval(candidateRetriever, textNormalizer),
                environment);
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

        // 第 3、4 步：文本归一化与 BM25 Top-K，交由与 Agent 平面共用的检索内核执行，
        // 使「检索域严格等于已授权子集」这条不变量只有一处实现。
        AuthorizedCandidateRetrieval.Retrieved retrieved =
                retrieval.retrieveWithin(rawText, visible, topK);
        return new Resolution(outcomeOf(retrieved.status()), snapshotVersion,
                retrieved.normalizedText(), retrieved.scopeSize(), retrieved.candidates(),
                retrieved.diagnosticReason() != null
                        ? retrieved.diagnosticReason()
                        : diagnosticReasonOf(retrieved.status()));
    }

    /**
     * 检索内核的结果分类到本服务对外分类的映射。
     *
     * <p>写成 {@code switch} 表达式：内核新增一种结果分类时编译器会在这里报缺分支，
     * 而 {@code if} 链只会静默落到某个既有分类上。</p>
     */
    private static Outcome outcomeOf(AuthorizedCandidateRetrieval.Retrieved.Status status) {
        return switch (status) {
            case RETRIEVED -> Outcome.RESOLVED;
            case EMPTY_SCOPE -> Outcome.NO_VISIBLE_CAPABILITY;
            case BLANK_TEXT -> Outcome.EMPTY_NORMALIZED_TEXT;
            case RETRIEVAL_FAILED -> Outcome.RETRIEVAL_FAILED;
        };
    }

    private static String diagnosticReasonOf(
            AuthorizedCandidateRetrieval.Retrieved.Status status) {
        return switch (status) {
            case RETRIEVED -> null;
            case EMPTY_SCOPE -> "principal has no visible capability";
            case BLANK_TEXT -> "normalized text is blank";
            case RETRIEVAL_FAILED -> "candidate retrieval failed";
        };
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
        return retrieval.indexedCatalogVersion();
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
