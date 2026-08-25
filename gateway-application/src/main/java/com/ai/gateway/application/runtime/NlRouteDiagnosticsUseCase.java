package com.ai.gateway.application.runtime;

import com.ai.gateway.application.agent.CapabilityPublicProjectionService;
import com.ai.gateway.application.catalog.CandidateResolutionService;
import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.ModelDecision;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.RoutingThresholds;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.CandidateRetriever;
import com.ai.gateway.domain.port.LlmRouterPort;
import com.ai.gateway.domain.service.AliasGenerator;
import com.ai.gateway.domain.service.ManifestDigest;
import com.ai.gateway.domain.service.Sha256Digest;
import com.ai.gateway.domain.service.ThresholdEvaluator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 管理面「能力目录诊断」用例：以 dry-run 方式复现运行面自然语言路由的完整判定链，
 * 并把每一步的中间态归因输出给清单作者。
 *
 * <p><b>为什么这条链路必须留在网关内部</b>：Manifest 中写给模型看的文本
 * （{@code displayName}、{@code purpose}、字段 {@code description}、正/负例与同义词）
 * 没有编译期正确性判据——它「写得对不对」只能通过实际跑一遍
 * 「授权过滤 → BM25 → 投影 → LLM 受限选择」来验证。而这次验证依赖的中间态
 * （授权过滤后的候选集、原始 BM25 分数、别名↔能力 ID 映射、可信字段剥离前后差异、
 * 注入检测命中）按设计永不越出网关边界，因此无法把该职责上交给上层 Agent。
 * 这就是运行面 LLM 路由内核<b>永久保留</b>而不是退役的原因。</p>
 *
 * <p><b>与运行面用例的关系</b>：本用例不是运行面的副本。它复用同一个
 * {@link CandidateResolutionService}（因而复用同一份授权前置过滤）、同一份
 * {@link RoutingThresholds#defaults()} 阈值与同一个 {@link ThresholdEvaluator}，
 * 只把「执行」替换为「归因」。任何一侧新增判定步骤都应加在共享组件里，而不是在
 * 两处各写一遍——否则诊断结论会与线上行为分叉，给出错误的修复建议。</p>
 *
 * <p><b>安全约束</b>（与设计文档 §2.6 一一对应）：</p>
 * <ol>
 * <li>仅管理面可达，调用方鉴权与管理员授权由 Web 层过滤器前置完成；本用例做
 * fail-closed 审计，平面标签固定为 {@link AuditPlane#GATEWAY_NL_DIAGNOSTIC}，
 * 使诊断流量不污染运行面成本与成功率口径。</li>
 * <li>被诊断视角的 {@code orgId} 恒取自管理员 {@link Principal}，请求体不得覆盖；
 * 请求只能替换角色与权限词，从而无法跨组织侦察目录。</li>
 * <li>响应不含 {@code ProtocolBinding}、服务地址、接口名与 {@code confirmationToken}；
 * 能力 ID 与版本属于管理面可见信息，用于定位清单，故予保留。</li>
 * <li>恒为 dry-run：不签发 {@code operationId}，不触达 Provider，无业务副作用。
 * 请求必须显式声明 {@code dryRun=true}，否则拒绝——让「不会执行」成为调用方的
 * 主动声明而非隐式约定。</li>
 * </ol>
 *
 * <p>线程安全：不持有任何按请求变化的可变状态。</p>
 *
 * @see NlRouterPolicy#diagnosticsAllowed()
 * @see DiagnosticsReport
 * @since 0.2.0
 */
public final class NlRouteDiagnosticsUseCase {

    /** 拒绝原因：请求未显式声明 dry-run。 */
    static final String REASON_DRY_RUN_REQUIRED = "DIAGNOSTICS_DRY_RUN_REQUIRED";

    /** 拒绝原因：查询文本为空。 */
    static final String REASON_QUERY_REQUIRED = "DIAGNOSTICS_QUERY_REQUIRED";

    /**
     * 诊断使用的阈值。刻意直接取运行面默认值而不允许按请求覆盖：
     * 可调阈值会让诊断变成「调参游戏」，而诊断的唯一价值是复现线上判定。
     */
    private static final RoutingThresholds THRESHOLDS = RoutingThresholds.defaults();

    private final CandidateResolutionService candidateResolutionService;
    private final CapabilityPublicProjectionService projectionService;
    private final AliasGenerator aliasGenerator;
    private final ThresholdEvaluator thresholdEvaluator;
    private final LlmRouterPort llmRouterPort;
    private final AuditPort auditPort;
    private final NlRouterPolicy routerPolicy;

    /**
     * @param candidateResolutionService 与运行面共用的候选解析内核（含授权前置过滤）
     * @param projectionService 模型可见投影服务，用于复现模型实际看到的文本与 Schema
     * @param aliasGenerator 别名生成器，用于复现别名↔能力 ID 映射
     * @param thresholdEvaluator 与运行面共用的阈值判定器
     * @param llmRouterPort LLM 受限选择内核；{@code null} 表示未装配（此时仅做检索归因）
     * @param auditPort 审计端口，诊断同样 fail-closed 落审计
     * @param routerPolicy 曝光策略，决定诊断端点是否可用
     */
    public NlRouteDiagnosticsUseCase(CandidateResolutionService candidateResolutionService,
                                     CapabilityPublicProjectionService projectionService,
                                     AliasGenerator aliasGenerator,
                                     ThresholdEvaluator thresholdEvaluator,
                                     LlmRouterPort llmRouterPort,
                                     AuditPort auditPort,
                                     NlRouterPolicy routerPolicy) {
        this.candidateResolutionService = Objects.requireNonNull(candidateResolutionService,
                "candidateResolutionService must not be null");
        this.projectionService = Objects.requireNonNull(projectionService,
                "projectionService must not be null");
        this.aliasGenerator = Objects.requireNonNull(aliasGenerator,
                "aliasGenerator must not be null");
        this.thresholdEvaluator = Objects.requireNonNull(thresholdEvaluator,
                "thresholdEvaluator must not be null");
        this.llmRouterPort = llmRouterPort;
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort must not be null");
        this.routerPolicy = Objects.requireNonNull(routerPolicy, "routerPolicy must not be null");
    }

    /**
     * 对给定查询执行一次只读诊断。
     *
     * <p>步骤与运行面严格同序：曝光门控 → 入参校验 → 构造被诊断视角 → 共享候选解析
     * （快照固定 / 授权过滤 / 归一化 / BM25）→ 模型可见投影 → 阈值判定 →
     * LLM 受限选择（可选）→ 别名归属校验 → fail-closed 审计。</p>
     *
     * <p>任何一步失败都以 {@link DiagnosticsReport} 表达而非抛异常：诊断的目的就是把
     * 失败原因说清楚，把失败转成异常会丢掉已经采集到的中间态。</p>
     *
     * @param adminPrincipal 已通过管理面鉴权的管理员身份，不可为 {@code null}
     * @param request 诊断请求，不可为 {@code null}
     * @return 诊断报告；永不为 {@code null}
     * @throws NullPointerException 任一入参为 {@code null} 时抛出
     */
    public DiagnosticsReport diagnose(Principal adminPrincipal, DiagnosticsRequest request) {
        Objects.requireNonNull(adminPrincipal, "adminPrincipal must not be null");
        Objects.requireNonNull(request, "request must not be null");
        long startTime = System.currentTimeMillis();

        // 第 0 步：曝光门控。DISABLED 档或部署侧关闭时诊断不可用。
        if (!routerPolicy.diagnosticsAllowed()) {
            return audit(DiagnosticsReport.rejected(Status.DISABLED,
                    ErrorCode.NL_ROUTER_DISABLED.name(),
                    "Catalog diagnostics is not enabled on this deployment"),
                    adminPrincipal, startTime);
        }
        // 第 1 步：入参校验。dryRun 必须由调用方显式声明。
        if (!request.dryRun()) {
            return audit(DiagnosticsReport.rejected(Status.REJECTED, REASON_DRY_RUN_REQUIRED,
                    "Diagnostics never executes a capability; dryRun must be true"),
                    adminPrincipal, startTime);
        }
        if (request.query() == null || request.query().isBlank()) {
            return audit(DiagnosticsReport.rejected(Status.REJECTED, REASON_QUERY_REQUIRED,
                    "query must not be blank"), adminPrincipal, startTime);
        }

        // 第 2 步：构造被诊断视角。orgId / 鉴权时间 / 鉴权方式恒沿用管理员身份，
        // 请求体只能替换角色与权限词，因此无法跨组织侦察目录。
        Principal subject = subjectView(adminPrincipal, request);
        int topK = effectiveTopK(request);

        // 第 3 步：共享候选解析。与运行面同一份授权前置过滤，无第二套过滤入口。
        CandidateResolutionService.Resolution resolution =
                candidateResolutionService.resolve(subject, request.query(), topK);
        if (!resolution.resolved()) {
            return audit(DiagnosticsReport.unresolved(resolution,
                    findingFor(resolution.outcome())), adminPrincipal, startTime);
        }

        // 第 4 步：复现模型可见投影与别名映射。
        long indexedCatalogVersion = candidateResolutionService.indexedCatalogVersion();
        List<CandidateView> views = projectCandidates(resolution);

        // 第 5 步：阈值判定（与运行面同一份阈值与判定器）。
        ThresholdEvaluator.ThresholdResult thresholdResult =
                thresholdEvaluator.evaluate(resolution.candidates(), THRESHOLDS);

        // 第 6 步：LLM 受限选择。仅在阈值判定为 SELECT 时执行——运行面同样只把
        // 阈值选出的 top-1 交给模型确认并抽参，诊断必须复现这一点而不是多送候选。
        ModelVerdict verdict = resolveModelVerdict(request, thresholdResult, views);

        Set<Finding> findings = collectFindings(resolution, indexedCatalogVersion, views,
                thresholdResult, verdict);
        return audit(new DiagnosticsReport(Status.COMPLETED, null, null,
                resolution.snapshotVersion(), indexedCatalogVersion,
                resolution.normalizedText(), resolution.visibleCapabilityCount(), views,
                thresholdVerdict(thresholdResult, views), verdict, List.copyOf(findings), 0L),
                adminPrincipal, startTime);
    }

    /**
     * 构造被诊断的授权视角。
     *
     * <p>只允许替换角色与权限词——这两者决定「能看到哪些能力」，正是诊断要观察的变量；
     * {@code orgId} 属于受信参数，只能来自管理员 {@link Principal}，请求体永不覆盖。</p>
     */
    private Principal subjectView(Principal adminPrincipal, DiagnosticsRequest request) {
        boolean overridden = !request.subjectRoles().isEmpty()
                || !request.subjectPermissions().isEmpty();
        if (!overridden) {
            return adminPrincipal;
        }
        return new Principal(adminPrincipal.subject(), adminPrincipal.orgId(),
                request.subjectRoles(), request.subjectPermissions(),
                adminPrincipal.authTime(), adminPrincipal.authMethod());
    }

    /** 归一化 Top-K：非正数取运行面阈值，并统一受策略上限截断，防止一次调用倾泻整个目录。 */
    private int effectiveTopK(DiagnosticsRequest request) {
        int requested = request.topK() > 0 ? request.topK() : THRESHOLDS.maxCandidates();
        return Math.min(requested, routerPolicy.diagnosticsMaxCandidates());
    }

    /** 把候选解析失败映射为诊断发现项，使失败也带可执行的修复提示。 */
    private static Finding findingFor(CandidateResolutionService.Outcome outcome) {
        return switch (outcome) {
            case SNAPSHOT_UNAVAILABLE -> Finding.SNAPSHOT_UNAVAILABLE;
            case EMPTY_CATALOG -> Finding.EMPTY_CATALOG;
            case AUTHORIZATION_UNAVAILABLE -> Finding.AUTHORIZATION_UNAVAILABLE;
            case NO_VISIBLE_CAPABILITY -> Finding.NO_VISIBLE_CAPABILITY;
            case EMPTY_NORMALIZED_TEXT -> Finding.EMPTY_NORMALIZED_TEXT;
            case RETRIEVAL_FAILED -> Finding.RETRIEVAL_FAILED;
            case RESOLVED -> throw new IllegalStateException(
                    "findingFor must not be called for RESOLVED");
        };
    }

    /**
     * 复现每个候选的模型可见投影。
     *
     * <p>投影为空表示注入检测命中——该能力在真实路由中同样会被剔出模型视图，
     * 这是清单文本最常见也最难自查的缺陷，因此单独标记而不是静默跳过。</p>
     */
    private List<CandidateView> projectCandidates(
            CandidateResolutionService.Resolution resolution) {
        List<CandidateView> views = new ArrayList<>();
        int rank = 0;
        for (CandidateRetriever.ScoredCapability scored : resolution.candidates()) {
            rank++;
            CapabilityManifest manifest = scored.capability();
            String alias = aliasGenerator.generate(resolution.snapshotVersion(),
                    manifest.metadata().id(), manifest.metadata().version());
            Optional<CapabilityPublicProjectionService.Projection> projection =
                    projectionService.project(manifest);
            Set<String> strippedFields = projectionService.trustedFieldNames(manifest);
            views.add(new CandidateView(rank, alias,
                    manifest.metadata().id(), manifest.metadata().version(),
                    ManifestDigest.sha256(manifest), scored.score(),
                    projection.map(CapabilityPublicProjectionService.Projection::displayName)
                            .orElse(""),
                    projection.map(CapabilityPublicProjectionService.Projection::purpose)
                            .orElse(""),
                    projection.map(CapabilityPublicProjectionService.Projection::schemaClass)
                            .orElse(null),
                    projection.map(CapabilityPublicProjectionService.Projection::argumentContract)
                            .orElse(Map.of()),
                    projection.isEmpty(), List.copyOf(strippedFields),
                    manifest.spec().risk()));
        }
        return List.copyOf(views);
    }

    /**
     * 复现运行面的 LLM 受限选择步骤。
     *
     * <p>跳过条件被显式记录在 {@link ModelVerdict#detail()} 中，而不是静默返回空值：
     * 「模型没被调用」与「模型判定不匹配」是完全不同的诊断结论，混淆二者会让清单作者
     * 去修改本就没进入模型视野的文本。</p>
     *
     * <p>{@code explain=false} 时不产生任何模型成本，使 golden query 基线可以低成本
     * 批量回归纯检索层。</p>
     */
    private ModelVerdict resolveModelVerdict(DiagnosticsRequest request,
                                             ThresholdEvaluator.ThresholdResult thresholdResult,
                                             List<CandidateView> views) {
        if (!request.explain()) {
            return ModelVerdict.skipped("explain=false: retrieval-only diagnostics");
        }
        if (thresholdResult.decision() != ThresholdEvaluator.Decision.SELECT) {
            return ModelVerdict.skipped("threshold decision is " + thresholdResult.decision()
                    + "; the runtime plane does not invoke the model in this case");
        }
        if (llmRouterPort == null) {
            return ModelVerdict.skipped("llm router kernel is not wired on this deployment");
        }
        CandidateRetriever.ScoredCapability selected = thresholdResult.selectedCandidate()
                .orElseThrow(() -> new IllegalStateException(
                        "SELECT decision without selected candidate"));
        CapabilityManifest manifest = selected.capability();
        String alias = aliasOf(views, manifest);
        // 与 DefaultSelectDecisionProcessor 相同的候选构造：只送别名与公开文本，
        // 绝不送 ProtocolBinding、服务地址、接口名、租户与身份。
        LlmRouterPort.LlmCandidate candidate = new LlmRouterPort.LlmCandidate(
                alias, manifest.spec().displayName(), manifest.spec().description(),
                manifest.spec().examples().positive(), manifest.spec().examples().negative(),
                manifest.spec().examples().synonyms(), manifest.spec().inputSchema());
        ModelDecision decision;
        try {
            decision = llmRouterPort.route(request.query(), List.of(candidate));
        } catch (LlmRouterPort.LlmRoutingException e) {
            return ModelVerdict.failed(e.errorCode().name(), e.getMessage());
        } catch (RuntimeException e) {
            return ModelVerdict.failed(ErrorCode.LLM_UNAVAILABLE.name(), e.getMessage());
        }
        return mapDecision(decision, alias);
    }

    /** 把模型决策映射为诊断结论，并复现「别名必须属于本请求候选集」这一确定性校验。 */
    private static ModelVerdict mapDecision(ModelDecision decision, String expectedAlias) {
        if (decision instanceof ModelDecision.SelectDecision select) {
            if (expectedAlias.equals(select.alias())) {
                return new ModelVerdict(ModelOutcome.SELECT, select.alias(), select.arguments(),
                        null, "model confirmed the threshold-selected candidate");
            }
            return new ModelVerdict(ModelOutcome.ALIAS_MISMATCH, select.alias(),
                    select.arguments(), ErrorCode.INVALID_MODEL_OUTPUT.name(),
                    "model returned an alias outside the candidate set");
        }
        if (decision instanceof ModelDecision.ClarifyDecision clarify) {
            return new ModelVerdict(ModelOutcome.CLARIFY, null, Map.of(),
                    ErrorCode.CLARIFICATION_REQUIRED.name(), clarify.question());
        }
        if (decision instanceof ModelDecision.NoMatchDecision noMatch) {
            return new ModelVerdict(ModelOutcome.NO_MATCH, null, Map.of(),
                    ErrorCode.NO_CAPABILITY_MATCH.name(), noMatch.reasonCode());
        }
        // sealed interface 已穷举三种实现；此分支仅为编译期完备性兜底。
        return ModelVerdict.failed(ErrorCode.PROTOCOL_ERROR.name(),
                "unsupported model decision type");
    }

    /** 在已投影的候选中回查别名，避免为同一能力重复生成一次别名而引入不一致风险。 */
    private static String aliasOf(List<CandidateView> views, CapabilityManifest manifest) {
        return views.stream()
                .filter(view -> view.capabilityId().equals(manifest.metadata().id())
                        && view.capabilityVersion().equals(manifest.metadata().version()))
                .map(CandidateView::alias)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "selected candidate is missing from the projected candidate set"));
    }

    /** 把阈值判定结果转成对外结论，并带上 top-1/top-2 分数使「差距不足」可量化。 */
    private static ThresholdVerdict thresholdVerdict(
            ThresholdEvaluator.ThresholdResult result, List<CandidateView> views) {
        double topScore = views.isEmpty() ? 0.0 : views.get(0).score();
        double runnerUpScore = views.size() >= 2 ? views.get(1).score() : 0.0;
        String selectedAlias = result.selectedCandidate()
                .map(scored -> aliasOf(views, scored.capability())).orElse(null);
        String selectedCapabilityId = result.selectedCandidate()
                .map(scored -> scored.capability().metadata().id()).orElse(null);
        return new ThresholdVerdict(result.decision(), selectedAlias, selectedCapabilityId,
                result.clarificationQuestion(), result.noMatchReason(),
                topScore, runnerUpScore,
                THRESHOLDS.minRelevanceScore(), THRESHOLDS.minTop1Top2ScoreDiff());
    }

    /**
     * 汇总可执行的诊断发现项。
     *
     * <p>这是本用例真正的产出：清单作者需要的不是一堆分数，而是「该改哪一段文本」。
     * 新增判据时只需在此追加一条 {@link Finding}，无需改动上游任何步骤。</p>
     */
    private static Set<Finding> collectFindings(CandidateResolutionService.Resolution resolution,
                                                long indexedCatalogVersion,
                                                List<CandidateView> views,
                                                ThresholdEvaluator.ThresholdResult thresholdResult,
                                                ModelVerdict verdict) {
        Set<Finding> findings = new LinkedHashSet<>();
        if (indexedCatalogVersion > 0 && indexedCatalogVersion != resolution.snapshotVersion()) {
            findings.add(Finding.RETRIEVAL_INDEX_STALE);
        }
        if (views.isEmpty()) {
            findings.add(Finding.NO_RETRIEVAL_HIT);
        }
        if (views.stream().anyMatch(CandidateView::projectionSuppressed)) {
            findings.add(Finding.PROJECTION_SUPPRESSED_BY_INJECTION_GUARD);
        }
        if (views.stream().anyMatch(view -> !view.strippedTrustedFields().isEmpty())) {
            findings.add(Finding.TRUSTED_FIELDS_STRIPPED);
        }
        if (views.stream().anyMatch(view ->
                view.schemaClass() == CapabilityPublicProjectionService.SchemaClass.COMPLEX)) {
            findings.add(Finding.SCHEMA_TOO_COMPLEX_FOR_MODEL);
        }
        switch (thresholdResult.decision()) {
            case NO_MATCH -> findings.add(Finding.THRESHOLD_NO_MATCH);
            case CLARIFY -> findings.add(Finding.TOP1_TOP2_TOO_CLOSE);
            case SELECT -> { /* 无需追加发现项 */ }
        }
        switch (verdict.outcome()) {
            case ALIAS_MISMATCH -> findings.add(Finding.MODEL_ALIAS_MISMATCH);
            case NO_MATCH -> findings.add(Finding.MODEL_REJECTED_CANDIDATE);
            case CLARIFY -> findings.add(Finding.MODEL_NEEDS_CLARIFICATION);
            case FAILED -> findings.add(Finding.MODEL_UNAVAILABLE);
            case SELECT, SKIPPED -> { /* 无需追加发现项 */ }
        }
        return findings;
    }

    /**
     * 以 fail-closed 语义落一条诊断审计，并回填耗时。
     *
     * <p>平面标签固定为 {@code gateway-nl-diagnostic}：诊断会真实消耗模型额度，
     * 若与运行面共用标签，诊断流量就会污染成本归属与成功率口径，
     * 使「NL 请求占运行面总量 &lt; 1%」这类曝光策略判据失效。</p>
     *
     * <p>{@code operationId} 恒为 {@code null}——诊断永不进入两阶段写协议。</p>
     */
    private DiagnosticsReport audit(DiagnosticsReport report, Principal adminPrincipal,
                                    long startTime) {
        long durationMs = System.currentTimeMillis() - startTime;
        DiagnosticsReport timed = report.withDurationMs(durationMs);
        String resultCode = timed.errorCode() != null ? timed.errorCode() : timed.status().name();
        auditPort.recordEvent(new AuditEvent(
                UUID.randomUUID().toString(), "CATALOG_DIAGNOSTIC", Instant.now(),
                Sha256Digest.sha256Hex(adminPrincipal.subject()), adminPrincipal.orgId(),
                UUID.randomUUID().toString(), null,
                timed.thresholdVerdict() == null ? null
                        : timed.thresholdVerdict().selectedCapabilityId(),
                null, null, timed.snapshotVersion(), null, null, resultCode,
                durationMs, AuditPlane.GATEWAY_NL_DIAGNOSTIC.detailsJson()));
        return timed;
    }

    /** 诊断整体状态。不承载 HTTP 语义，由 Web 层映射。 */
    public enum Status {
        /** 诊断链路完整走通（不代表路由成功，失败归因见 findings）。 */
        COMPLETED,
        /** 诊断端点未开启（{@code DISABLED} 档或部署侧关闭）。 */
        DISABLED,
        /** 请求本身不合法（未声明 dry-run、查询为空）。 */
        REJECTED,
        /** 候选解析在进入投影之前失败（快照、授权、归一化、检索层面）。 */
        UNRESOLVED
    }

    /** 模型环节的结论。 */
    public enum ModelOutcome {
        /** 模型确认了阈值选出的候选。 */
        SELECT,
        /** 模型认为候选不匹配。 */
        NO_MATCH,
        /** 模型要求澄清。 */
        CLARIFY,
        /** 模型返回了候选集之外的别名——确定性校验拦下，属清单/提示词缺陷。 */
        ALIAS_MISMATCH,
        /** 模型调用失败。 */
        FAILED,
        /** 未调用模型（explain=false、阈值未选中或内核未装配）。 */
        SKIPPED
    }

    /**
     * 可执行的诊断发现项：稳定枚举名 + 面向清单作者的中文修复提示。
     *
     * <p>枚举名是对外稳定契约，golden query 基线断言依赖它，重命名等同破坏基线；
     * {@link #hint()} 只用于管理台展示，可自由调整措辞。</p>
     */
    public enum Finding {

        /** 目录快照不可用。 */
        SNAPSHOT_UNAVAILABLE("目录快照加载失败，请先确认该环境已发布快照且数据源可用。"),

        /** 环境内没有任何已发布能力。 */
        EMPTY_CATALOG("该环境没有任何已发布能力，请先完成导入、审批与发布。"),

        /** 授权数据源不可用（fail-closed）。 */
        AUTHORIZATION_UNAVAILABLE("授权数据源不可用，已按 fail-closed 处理；请检查 ACL 策略加载状态。"),

        /** 被诊断视角没有任何可见能力。 */
        NO_VISIBLE_CAPABILITY("该角色/权限视角下没有任何可见能力，请检查能力的 authorization 配置与 ACL 条目。"),

        /** 归一化后文本为空。 */
        EMPTY_NORMALIZED_TEXT("查询文本经停用词与标点归一化后为空，无法检索；请改用含业务名词的表述。"),

        /** 检索引擎调用失败。 */
        RETRIEVAL_FAILED("BM25 检索器调用失败，请检查索引状态与检索器健康度。"),

        /** 检索索引版本落后于已发布快照。 */
        RETRIEVAL_INDEX_STALE("检索索引版本落后于已发布快照，命中结果可能不反映最新清单；等待索引重建后重试。"),

        /** 检索零命中。 */
        NO_RETRIEVAL_HIT("检索零命中：清单的 displayName / purpose / 正例 / 同义词缺少用户实际使用的词汇。"),

        /** 注入检测命中，能力被剔出模型视图。 */
        PROJECTION_SUPPRESSED_BY_INJECTION_GUARD(
                "清单文本命中提示词注入检测，该能力已被剔出模型视图；请移除描述/示例中的指令式语句。"),

        /** 存在被剥离的受信字段。 */
        TRUSTED_FIELDS_STRIPPED(
                "部分参数来源非 MODEL，已从模型可见 Schema 中剥离；请确认这些字段确实应由网关注入。"),

        /** 公开 Schema 过于复杂，模型抽参可靠性下降。 */
        SCHEMA_TOO_COMPLEX_FOR_MODEL(
                "公开入参 Schema 被判定为 COMPLEX（组合关键字/嵌套过深/字段过多），建议拆分能力或简化契约。"),

        /** top-1 分数低于最低相关度。 */
        THRESHOLD_NO_MATCH("top-1 分数低于最低相关度阈值，请补充正例与同义词以提升该查询下的相关性。"),

        /** top-1/top-2 分差不足，判定为歧义。 */
        TOP1_TOP2_TOO_CLOSE(
                "top-1 与 top-2 分差不足，被判定为歧义；请让两个能力的 purpose 与负例明确互斥。"),

        /** 模型返回候选集外别名。 */
        MODEL_ALIAS_MISMATCH("模型返回了候选集之外的别名，已被确定性校验拦下；请检查提示词模板与模型版本。"),

        /** 模型判定候选不匹配。 */
        MODEL_REJECTED_CANDIDATE(
                "检索选中了该能力但模型判定不匹配，说明清单描述与检索关键词不一致；请对齐 purpose 与正例。"),

        /** 模型要求澄清。 */
        MODEL_NEEDS_CLARIFICATION("模型要求澄清，通常是必填参数在描述中缺少可抽取线索；请完善字段 description。"),

        /** 模型不可用。 */
        MODEL_UNAVAILABLE("LLM 调用失败，本次仅检索层结论可信；请检查模型 Provider 健康度与凭据。");

        private final String hint;

        Finding(String hint) {
            this.hint = hint;
        }

        /** @return 面向清单作者的中文修复提示 */
        public String hint() {
            return hint;
        }
    }

    /**
     * 诊断请求。
     *
     * <p>刻意不含 {@code orgId} 字段：组织上下文是受信参数，只能来自管理员
     * {@link Principal}。若把它放进请求体，管理员就能跨组织侦察能力目录。</p>
     *
     * <p>被诊断视角用「角色 + 权限词」而非用户名表达：网关不持有身份系统的用户→角色
     * 映射（{@code OrgMembershipPort} 只能校验成员关系），凭用户名伪造视角会给出与真实
     * 授权不一致的结论。改用显式角色集合后，诊断语义是精确的——「同组织内持有这组角色的
     * 主体会看到什么」。</p>
     *
     * @param query 待诊断的自然语言查询，不可为空白
     * @param subjectRoles 被诊断视角的角色集合；为空表示沿用管理员自身视角
     * @param subjectPermissions 被诊断视角的权限词集合；为空表示沿用管理员自身视角
     * @param topK 候选数上限；非正表示取运行面默认值，最终统一受策略上限截断
     * @param explain 是否执行 LLM 受限选择；{@code false} 时仅输出检索层归因且零模型成本
     * @param dryRun 必须显式为 {@code true}；诊断永不执行能力
     */
    public record DiagnosticsRequest(String query,
                                     List<String> subjectRoles,
                                     List<String> subjectPermissions,
                                     int topK,
                                     boolean explain,
                                     boolean dryRun) {

        public DiagnosticsRequest {
            subjectRoles = subjectRoles == null ? List.of() : List.copyOf(subjectRoles);
            subjectPermissions = subjectPermissions == null
                    ? List.of() : List.copyOf(subjectPermissions);
        }

        /**
         * 构造以管理员自身视角、开启模型归因的标准诊断请求。
         *
         * @param query 自然语言查询
         * @return 诊断请求
         */
        public static DiagnosticsRequest of(String query) {
            return new DiagnosticsRequest(query, List.of(), List.of(), 0, true, true);
        }

        /**
         * 构造仅检索层、零模型成本的诊断请求，供 golden query 基线批量回归使用。
         *
         * @param query 自然语言查询
         * @return 诊断请求
         */
        public static DiagnosticsRequest retrievalOnly(String query) {
            return new DiagnosticsRequest(query, List.of(), List.of(), 0, false, true);
        }
    }

    /**
     * 单个候选的诊断视图：既给出检索层事实（排名、原始分数），也给出模型实际看到的投影。
     *
     * <p>刻意<b>不含</b> {@code ProtocolBinding}、服务地址、接口名与任何凭据；
     * {@code capabilityId} 与版本属于管理面可见信息，用于定位要修改的清单文件。</p>
     *
     * @param rank 按分数降序的排名，从 1 开始
     * @param alias 模型实际看到的短别名（{@code cap_<hash>}）
     * @param capabilityId 真实能力 ID，仅管理面可见
     * @param capabilityVersion 能力版本
     * @param manifestDigest 清单 SHA-256 摘要，用于确认诊断针对的正是当前清单
     * @param score 原始 BM25 分数
     * @param displayName 模型可见的展示名（已投影与截断）
     * @param purpose 模型可见的用途描述（已投影与截断）
     * @param schemaClass 公开 Schema 复杂度分级；投影被抑制时为 {@code null}
     * @param argumentContract 模型可见的紧凑参数契约
     * @param projectionSuppressed 是否因注入检测命中而被剔出模型视图
     * @param strippedTrustedFields 因来源非 MODEL 而被剥离的根级字段名
     * @param risk 能力风险等级，用于解释入口点风险门控
     */
    public record CandidateView(int rank,
                                String alias,
                                String capabilityId,
                                String capabilityVersion,
                                String manifestDigest,
                                double score,
                                String displayName,
                                String purpose,
                                CapabilityPublicProjectionService.SchemaClass schemaClass,
                                Map<String, Object> argumentContract,
                                boolean projectionSuppressed,
                                List<String> strippedTrustedFields,
                                RiskLevel risk) {

        public CandidateView {
            argumentContract = argumentContract == null ? Map.of() : Map.copyOf(argumentContract);
            strippedTrustedFields = strippedTrustedFields == null
                    ? List.of() : List.copyOf(strippedTrustedFields);
        }
    }

    /**
     * 阈值判定结论。同时输出实际分数与生效阈值，使「差多少」可量化，
     * 避免清单作者只能靠反复试错猜测需要提升多少相关性。
     *
     * @param decision 阈值判定
     * @param selectedAlias 被选中候选的别名；未选中为 {@code null}
     * @param selectedCapabilityId 被选中候选的能力 ID；未选中为 {@code null}
     * @param clarificationQuestion 歧义时的澄清问题；否则为空串
     * @param noMatchReason 无匹配归因；否则为空串
     * @param topScore top-1 原始分数
     * @param runnerUpScore top-2 原始分数；候选不足 2 个时为 0
     * @param minRelevanceScore 生效的最低相关度阈值
     * @param minTop1Top2ScoreDiff 生效的最小分差阈值
     */
    public record ThresholdVerdict(ThresholdEvaluator.Decision decision,
                                   String selectedAlias,
                                   String selectedCapabilityId,
                                   String clarificationQuestion,
                                   String noMatchReason,
                                   double topScore,
                                   double runnerUpScore,
                                   double minRelevanceScore,
                                   double minTop1Top2ScoreDiff) {
    }

    /**
     * 模型环节结论。
     *
     * @param outcome 结论分类
     * @param returnedAlias 模型返回的别名；未调用或无选择时为 {@code null}
     * @param extractedArguments 模型抽取的 MODEL 来源参数，用于诊断抽参质量
     * @param errorCode 对应的稳定错误码；正常选中时为 {@code null}
     * @param detail 归因文本（跳过原因、澄清问题、原因码或失败摘要）
     */
    public record ModelVerdict(ModelOutcome outcome,
                               String returnedAlias,
                               Map<String, Object> extractedArguments,
                               String errorCode,
                               String detail) {

        public ModelVerdict {
            Objects.requireNonNull(outcome, "outcome must not be null");
            extractedArguments = extractedArguments == null
                    ? Map.of() : Map.copyOf(extractedArguments);
        }

        static ModelVerdict skipped(String reason) {
            return new ModelVerdict(ModelOutcome.SKIPPED, null, Map.of(), null, reason);
        }

        static ModelVerdict failed(String errorCode, String detail) {
            return new ModelVerdict(ModelOutcome.FAILED, null, Map.of(), errorCode, detail);
        }
    }

    /**
     * 诊断报告。
     *
     * <p>该记录是管理面对外契约的载体，其中<b>不得</b>出现 {@code ProtocolBinding}、
     * 服务地址、接口名、租户标识与 {@code confirmationToken}；也永不包含
     * {@code operationId}——诊断恒为 dry-run。</p>
     *
     * @param status 整体状态
     * @param errorCode 稳定错误码或发现项名；{@code COMPLETED} 时为 {@code null}
     * @param message 归因摘要；仅管理面可见
     * @param snapshotVersion 本次固定的目录快照版本
     * @param indexedCatalogVersion 检索器已建索引的目录版本，用于识别索引漂移
     * @param normalizedText 归一化后的检索词，用于解释「为什么没命中」
     * @param visibleCapabilityCount 授权过滤后的可见能力总数
     * @param candidates 候选诊断视图，按分数降序
     * @param thresholdVerdict 阈值判定结论；未进行到该步时为 {@code null}
     * @param modelVerdict 模型环节结论；永不为 {@code null}
     * @param findings 可执行的发现项集合，去重且保持插入顺序
     * @param durationMs 诊断耗时（毫秒），由审计环节回填
     */
    public record DiagnosticsReport(Status status,
                                   String errorCode,
                                   String message,
                                   long snapshotVersion,
                                   long indexedCatalogVersion,
                                   String normalizedText,
                                   int visibleCapabilityCount,
                                   List<CandidateView> candidates,
                                   ThresholdVerdict thresholdVerdict,
                                   ModelVerdict modelVerdict,
                                   List<Finding> findings,
                                   long durationMs) {

        public DiagnosticsReport {
            Objects.requireNonNull(status, "status must not be null");
            normalizedText = normalizedText == null ? "" : normalizedText;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            findings = findings == null ? List.of() : List.copyOf(findings);
            modelVerdict = modelVerdict == null
                    ? ModelVerdict.skipped("not reached") : modelVerdict;
        }

        static DiagnosticsReport rejected(Status status, String errorCode, String message) {
            return new DiagnosticsReport(status, errorCode, message, 0L, 0L, "", 0,
                    List.of(), null, ModelVerdict.skipped("request rejected before routing"),
                    List.of(), 0L);
        }

        static DiagnosticsReport unresolved(CandidateResolutionService.Resolution resolution,
                                           Finding finding) {
            return new DiagnosticsReport(Status.UNRESOLVED, finding.name(),
                    resolution.diagnosticReason(), resolution.snapshotVersion(), 0L,
                    resolution.normalizedText(), resolution.visibleCapabilityCount(),
                    List.of(), null,
                    ModelVerdict.skipped("candidate resolution failed before the model step"),
                    List.of(finding), 0L);
        }

        /**
         * 返回回填耗时后的副本。
         *
         * @param elapsedMs 实测耗时
         * @return 新的报告实例
         */
        DiagnosticsReport withDurationMs(long elapsedMs) {
            return new DiagnosticsReport(status, errorCode, message, snapshotVersion,
                    indexedCatalogVersion, normalizedText, visibleCapabilityCount, candidates,
                    thresholdVerdict, modelVerdict, findings, elapsedMs);
        }

        /** @return 诊断链路是否完整走通 */
        public boolean completed() {
            return status == Status.COMPLETED;
        }
    }
}
