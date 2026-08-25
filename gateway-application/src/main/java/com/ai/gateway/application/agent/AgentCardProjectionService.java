package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.TrustTier;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 分级 AgentCard 投影：把「先授权后暴露」落到 A2A 的卡片协商上。
 *
 * <p>A2A 的能力发现是两层的，本服务对应产出两种卡：</p>
 * <ul>
 * <li><b>公开卡</b>（{@code GET /.well-known/agent-card.json}）——身份无关，
 * {@code skills} 恒为空列表。公开卡若携带 skills，就等于向任何匿名访问者公开一份能力目录。</li>
 * <li><b>扩展卡</b>（{@code agent/getAuthenticatedExtendedCard}）——按调用方身份生成，
 * 只包含该 peer 有权使用的<b>业务域</b>。</li>
 * </ul>
 *
 * <p>四条不可放松的投影规则：</p>
 * <ol>
 * <li><b>粒度是业务域</b>：{@code skill.id = domain.<域>}，域取自 {@code capabilityId} 的第一段，
 * 聚合后去重；单个 Capability 永不单独成为 skill，真实 {@code capabilityId} 不出现在任何字段。</li>
 * <li><b>标签用固定词表</b>：{@code read-only} / {@code requires-confirmation}，
 * 不暴露 {@link RiskLevel} 枚举名本身；{@link RiskLevel#WRITE_HIGH} 不参与投影也不影响标签。</li>
 * <li><b>示例先过治理</b>：经 {@link CapabilityPublicProjectionService} 的注入检测与归一化，
 * 命中注入模式的域<b>整域剔除</b>——同一域内既然有清单尝试过注入，该域的自然语言内容
 * 就不再具备可信度。</li>
 * <li><b>授权在聚合之前</b>：投影输入是「目录快照 ∩ 该身份的可见集合」，不是全量清单仓库。</li>
 * </ol>
 *
 * <p>本服务是纯 Java：不持有任何端口，不做认证与鉴权。它接收的是<b>已经完成</b>的授权结果
 * （{@link PolicySnapshot}），因此可以被入站端点、管理面诊断与测试以完全相同的方式复用，
 * 而不必各自模拟一套认证链。</p>
 *
 * <p>本类线程安全：除有界缓存外无可变状态。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class AgentCardProjectionService {

    /** 单张扩展卡最多投影的业务域数量。 */
    private static final int MAX_SKILLS = 32;

    /** 单个业务域最多附带的示例条数。 */
    private static final int MAX_EXAMPLES_PER_SKILL = 5;

    /** 业务域描述的长度上限。 */
    private static final int MAX_SKILL_DESCRIPTION = 240;

    /** 扩展卡缓存的条目上限，防止 peer 数量或纪元抖动把缓存变成内存泄漏点。 */
    private static final int DEFAULT_CACHE_CAPACITY = 256;

    /** 固定标签：只读语义。 */
    private static final String TAG_READ_ONLY = "read-only";

    /** 固定标签：需要用户确认（两阶段写）。 */
    private static final String TAG_REQUIRES_CONFIRMATION = "requires-confirmation";

    /** 默认媒体类型：A2A 的 Part 形态由适配层决定，这里只声明两种通用形态。 */
    private static final List<String> DEFAULT_MODES =
            List.of("text/plain", "application/json");

    /** 域标识允许的字符集之外的一切字符都会被剔除。 */
    private static final java.util.regex.Pattern DOMAIN_DISALLOWED =
            java.util.regex.Pattern.compile("[^a-z0-9-]");

    private final AgentDescriptor descriptor;
    private final CapabilityPublicProjectionService projectionService;
    private final SkillNarrative narratives;
    private final int cacheCapacity;
    private final Map<CacheKey, AgentCardProjection> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Baseline> baseline =
            new AtomicReference<>(new Baseline(0L, 0L));

    /**
     * 使用派生式域叙述（域名即标识、描述由成员能力的用途拼接）构造服务。
     *
     * @param descriptor        网关自身的 Agent 描述
     * @param projectionService 公开投影服务，提供注入检测与归一化
     */
    public AgentCardProjectionService(AgentDescriptor descriptor,
                                      CapabilityPublicProjectionService projectionService) {
        this(descriptor, projectionService, SkillNarrative.derived(), DEFAULT_CACHE_CAPACITY);
    }

    /**
     * @param narratives    域叙述策略：企业通常希望用受治理的中文域名替换派生结果，
     *                      因此这里留成可替换的策略而不是硬编码（开闭原则）
     * @param cacheCapacity 扩展卡缓存条目上限，必须为正
     */
    public AgentCardProjectionService(AgentDescriptor descriptor,
                                      CapabilityPublicProjectionService projectionService,
                                      SkillNarrative narratives,
                                      int cacheCapacity) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.projectionService = Objects.requireNonNull(
                projectionService, "projectionService must not be null");
        this.narratives = Objects.requireNonNull(narratives, "narratives must not be null");
        if (cacheCapacity <= 0) {
            throw new IllegalArgumentException("cacheCapacity must be positive");
        }
        this.cacheCapacity = cacheCapacity;
    }

    /**
     * 生成身份无关的公开卡。
     *
     * <p>{@code skills} 恒为空列表，且该性质<b>不可配置</b>：一旦允许公开卡携带 skills，
     * 未认证访问者就能枚举企业的业务域构成。</p>
     *
     * @return 公开卡投影
     */
    public AgentCardProjection publicCard() {
        return card(List.of());
    }

    /**
     * 生成按调用方身份裁剪的扩展卡。
     *
     * <p>任一前置条件不成立（未认证 peer、策略快照不健康、纪元或目录版本非法）时，
     * 返回的就是一张公开卡——<b>失效关闭</b>：宁可让对端看不见任何业务域，
     * 也不能在授权结论不确定时投影出可见面。</p>
     *
     * <p>结果按 {@code (peerDigest, trustTier, catalogVersion, policyEpoch)} 缓存。
     * 键里额外包含 {@code trustTier}：peer 的信任分级可能在注册表侧被下调而目录与纪元都未变，
     * 若不入键，被降级的 peer 会继续读到那张更宽的卡。缓存在观测到更大的
     * {@code catalogVersion} 或 {@code policyEpoch} 时整体清空，因此撤权只要按既有约定推进纪元
     * 即刻生效，不依赖 TTL 过期。</p>
     *
     * @param request 投影请求，不能为 {@code null}
     * @return 扩展卡投影
     */
    public AgentCardProjection extendedCard(ExtendedCardRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        long catalogVersion = request.view().catalogVersion();
        long policyEpoch = request.policySnapshot().policyEpoch();
        if (!projectable(request) || !advanceBaseline(catalogVersion, policyEpoch)) {
            // 落后于已观测基线的请求同样不入缓存：把旧纪元的结论写回缓存等于把撤权回滚。
            return card(skills(request));
        }
        CacheKey key = new CacheKey(request.identity().peerDigest(),
                request.identity().trustTier(), catalogVersion, policyEpoch);
        AgentCardProjection cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        AgentCardProjection card = card(skills(request));
        if (cache.size() < cacheCapacity) {
            cache.putIfAbsent(key, card);
        }
        return card;
    }

    /** 投影前置条件：任一不成立即退回公开卡（失效关闭）。 */
    private static boolean projectable(ExtendedCardRequest request) {
        PolicySnapshot policySnapshot = request.policySnapshot();
        return request.identity().trustTier() != TrustTier.UNTRUSTED
                && policySnapshot.healthy()
                && policySnapshot.policyEpoch() > 0
                && request.view().catalogVersion() > 0;
    }

    /**
     * 推进缓存基线。
     *
     * <p>观测到更大的目录版本或纪元即整体清空缓存；观测到更小的值则拒绝缓存本次结果。
     * 「清空」而非「按键失效」是刻意的：纪元推进意味着<b>所有</b> peer 的授权结论都可能变化，
     * 逐键失效需要枚举全部 peer，而枚举本身就是一份 peer 名册。</p>
     *
     * @return 本次结果可以进入缓存时返回 {@code true}
     */
    private boolean advanceBaseline(long catalogVersion, long policyEpoch) {
        while (true) {
            Baseline current = baseline.get();
            if (catalogVersion < current.catalogVersion()
                    || policyEpoch < current.policyEpoch()) {
                return false;
            }
            if (catalogVersion == current.catalogVersion()
                    && policyEpoch == current.policyEpoch()) {
                return true;
            }
            if (baseline.compareAndSet(current, new Baseline(catalogVersion, policyEpoch))) {
                cache.clear();
                return true;
            }
        }
    }

    /** 用当前描述与给定技能列表组装一张卡。 */
    private AgentCardProjection card(List<AgentCardProjection.SkillProjection> skills) {
        return new AgentCardProjection(descriptor.agentName(), descriptor.description(),
                descriptor.publicUrl(), descriptor.version(), true,
                DEFAULT_MODES, DEFAULT_MODES, skills);
    }

    /**
     * 把该身份可见的能力聚合成业务域粒度的技能列表。
     *
     * <p>顺序是有意义的：<b>先按信任分级过滤风险，再做注入检测</b>。{@link RiskLevel#WRITE_HIGH}
     * 能力根本不构成投影输入，因此它的自然语言内容也不该把整个域连坐剔除——
     * 反过来实现会让一条从不投影的高风险清单具备「让整个域从卡片上消失」的能力。</p>
     */
    private List<AgentCardProjection.SkillProjection> skills(ExtendedCardRequest request) {
        if (!projectable(request)) {
            return List.of();
        }
        List<CapabilityManifest> visible = request.view()
                .visibleCapabilities(request.policySnapshot().visibility());
        Set<String> visibleIds = new LinkedHashSet<>();
        for (CapabilityManifest manifest : visible) {
            if (manifest != null) {
                visibleIds.add(manifest.metadata().id());
            }
        }
        Map<String, DomainAggregate> byDomain = new LinkedHashMap<>();
        Set<String> poisoned = new LinkedHashSet<>();
        for (CapabilityManifest manifest : visible) {
            if (manifest == null
                    || !request.identity().allowsProjection(manifest.spec().risk())) {
                continue;
            }
            String domain = domainOf(manifest);
            if (domain == null || poisoned.contains(domain)) {
                continue;
            }
            Optional<CapabilityPublicProjectionService.Projection> projection =
                    projectionService.project(manifest);
            if (projection.isEmpty()) {
                // 注入检测命中：整域剔除，且此后该域不再接受任何成员（顺序无关）。
                poisoned.add(domain);
                byDomain.remove(domain);
                continue;
            }
            byDomain.computeIfAbsent(domain, DomainAggregate::new).add(
                    manifest.spec().risk(), projection.get().purpose(),
                    projectionService.publicExamples(manifest, MAX_EXAMPLES_PER_SKILL),
                    visibleIds);
        }
        List<AgentCardProjection.SkillProjection> skills =
                new ArrayList<>(Math.min(byDomain.size(), MAX_SKILLS));
        for (DomainAggregate aggregate : byDomain.values()) {
            if (skills.size() >= MAX_SKILLS) {
                break;
            }
            skills.add(aggregate.toSkill(narratives));
        }
        return List.copyOf(skills);
    }

    /**
     * 取 {@code capabilityId} 的第一段作为业务域。
     *
     * <p>域是唯一被允许公开的目录结构信息：它的基数远小于能力数，且不足以定位某个具体接口。
     * 剩余段（如 {@code detail.query}）一律丢弃。</p>
     *
     * @return 归一化后的域标识；无法归一化时返回 {@code null}（该能力不参与投影）
     */
    private static String domainOf(CapabilityManifest manifest) {
        String id = manifest.metadata().id();
        if (id == null || id.isBlank()) {
            return null;
        }
        int dot = id.indexOf('.');
        String first = dot >= 0 ? id.substring(0, dot) : id;
        String domain = DOMAIN_DISALLOWED.matcher(
                first.trim().toLowerCase(Locale.ROOT)).replaceAll("");
        return domain.isEmpty() ? null : domain;
    }

    /**
     * 判定一段自然语言是否泄漏了真实 {@code capabilityId}。
     *
     * <p>清单的 {@code description} / {@code examples} 是业务 Owner 手写的，完全可能顺手写进
     * 接口标识。域粒度投影的全部价值就在于不公开 {@code capabilityId}，因此这里做一次
     * 兜底剔除，而不是指望清单作者永远不这么写。</p>
     */
    private static boolean leaksCapabilityId(String text, Set<String> visibleIds) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String id : visibleIds) {
            if (id != null && !id.isBlank() && text.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 网关自身的 Agent 描述（对应 AgentCard 的固定头部字段）。
     *
     * @param agentName   Agent 名称，如 {@code capability-gateway}
     * @param description 用途描述
     * @param publicUrl   对外可达的 A2A 端点
     * @param version     网关版本
     */
    public record AgentDescriptor(String agentName, String description,
                                  String publicUrl, String version) {

        /**
         * 紧凑构造器。
         *
         * @param agentName   Agent 名称
         * @param description 用途描述
         * @param publicUrl   端点地址
         * @param version     版本
         */
        public AgentDescriptor {
            requireText(agentName, "agentName");
            requireText(description, "description");
            requireText(publicUrl, "publicUrl");
            requireText(version, "version");
        }
    }

    /**
     * 扩展卡投影请求。
     *
     * <p>刻意接收 {@link PolicySnapshot} 整体而不是「可见集合 + 纪元」两个参数：
     * 后者允许把 A 纪元的可见集合与 B 纪元的编号配对，而快照本身已经强制两者一致。</p>
     *
     * @param identity       调用方工作负载身份（含网关侧判定的信任分级）
     * @param view           运行面只读目录视图
     * @param policySnapshot 该身份的授权结果，授权发生在本服务之外
     */
    public record ExtendedCardRequest(AgentIdentity identity, ActiveCatalogView view,
                                      PolicySnapshot policySnapshot) {

        /**
         * 紧凑构造器。
         *
         * @param identity       调用方身份
         * @param view           目录视图
         * @param policySnapshot 授权快照
         */
        public ExtendedCardRequest {
            Objects.requireNonNull(identity, "identity must not be null");
            Objects.requireNonNull(view, "view must not be null");
            Objects.requireNonNull(policySnapshot, "policySnapshot must not be null");
        }
    }

    /**
     * 业务域的展示名与描述来源。
     *
     * <p>派生结果（域标识 + 成员用途拼接）在工程上够用，但企业通常希望在卡片上看到
     * 「订单域」这类受治理的中文名称。做成策略而非配置读取，是为了让替换来源
     * （静态字典、管理面配置、i18n 资源）不需要改动本服务（开闭原则）。</p>
     */
    @FunctionalInterface
    public interface SkillNarrative {

        /**
         * 解析某个业务域的叙述。
         *
         * @param domain 归一化后的域标识
         * @return 受治理的叙述；未配置时返回 {@link Optional#empty()} 以回退到派生结果
         */
        Optional<Narrative> resolve(String domain);

        /** @return 永远回退到派生结果的策略（默认） */
        static SkillNarrative derived() {
            return domain -> Optional.empty();
        }

        /**
         * @param narratives 域标识到叙述的静态映射，{@code null} 视为空映射
         * @return 查表策略
         */
        static SkillNarrative of(Map<String, Narrative> narratives) {
            Map<String, Narrative> copy = narratives == null
                    ? Map.of() : Map.copyOf(narratives);
            return domain -> Optional.ofNullable(copy.get(domain));
        }
    }

    /**
     * 一个业务域的受治理叙述。
     *
     * @param name        展示名
     * @param description 用途描述
     */
    public record Narrative(String name, String description) {

        /**
         * 紧凑构造器。
         *
         * @param name        展示名
         * @param description 用途描述
         */
        public Narrative {
            requireText(name, "name");
            description = description == null ? "" : description;
        }
    }

    /** 单个业务域的聚合器：按成员能力累积风险语义、用途与示例。 */
    private static final class DomainAggregate {

        private final String domain;
        private final EnumSet<RiskLevel> risks = EnumSet.noneOf(RiskLevel.class);
        private final LinkedHashSet<String> purposes = new LinkedHashSet<>();
        private final LinkedHashSet<String> examples = new LinkedHashSet<>();

        private DomainAggregate(String domain) {
            this.domain = domain;
        }

        private void add(RiskLevel risk, String purpose, List<String> candidateExamples,
                         Set<String> visibleIds) {
            risks.add(risk);
            if (purpose != null && !purpose.isBlank()
                    && !leaksCapabilityId(purpose, visibleIds)) {
                purposes.add(purpose);
            }
            for (String example : candidateExamples) {
                if (examples.size() >= MAX_EXAMPLES_PER_SKILL) {
                    break;
                }
                if (!leaksCapabilityId(example, visibleIds)) {
                    examples.add(example);
                }
            }
        }

        private AgentCardProjection.SkillProjection toSkill(SkillNarrative narratives) {
            Optional<Narrative> narrative = narratives.resolve(domain);
            String name = narrative.map(Narrative::name)
                    .filter(value -> !value.isBlank())
                    .orElse(domain);
            String description = narrative.map(Narrative::description)
                    .filter(value -> !value.isBlank())
                    .orElseGet(this::derivedDescription);
            return new AgentCardProjection.SkillProjection("domain." + domain, name,
                    description, tags(), List.copyOf(examples));
        }

        /** 标签顺序固定，且只表达执行语义，不映射 {@link RiskLevel} 枚举名。 */
        private List<String> tags() {
            List<String> tags = new ArrayList<>(2);
            if (risks.contains(RiskLevel.READ_ONLY)) {
                tags.add(TAG_READ_ONLY);
            }
            if (risks.contains(RiskLevel.WRITE_LOW)) {
                tags.add(TAG_REQUIRES_CONFIRMATION);
            }
            return List.copyOf(tags);
        }

        /** 由成员能力的已治理用途拼出域描述，超长即截断。 */
        private String derivedDescription() {
            StringBuilder result = new StringBuilder();
            for (String purpose : purposes) {
                if (result.length() + purpose.length() + 1 > MAX_SKILL_DESCRIPTION) {
                    break;
                }
                if (result.length() > 0) {
                    result.append('；');
                }
                result.append(purpose);
            }
            return result.toString();
        }
    }

    /** 扩展卡缓存键。 */
    private record CacheKey(String peerDigest, TrustTier trustTier,
                            long catalogVersion, long policyEpoch) {
    }

    /** 已观测到的最新目录版本与策略纪元。 */
    private record Baseline(long catalogVersion, long policyEpoch) {
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
