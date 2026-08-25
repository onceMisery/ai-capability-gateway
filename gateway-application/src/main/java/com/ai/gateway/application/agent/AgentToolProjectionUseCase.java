package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CapabilityVisibility;
import com.ai.gateway.domain.model.PolicySnapshot;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.AliasGenerator;
import com.ai.gateway.domain.service.PrincipalFingerprint;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 无查询词的能力直投用例：把当前 Principal 已授权的只读能力投影成一份可直接摆进
 * {@code tools/list} 的工具清单，并在 {@code tools/call} 到达时把 alias 换成执行凭据。
 *
 * <p>它与 {@link AgentCapabilityResolver}、
 * {@link com.ai.gateway.application.runtime.AgentToolCatalogUseCase} 的分工是明确的：
 * 后两者都是「先有自然语言查询、再检索候选」的路径，而通用 MCP 客户端在 {@code tools/list}
 * 阶段没有任何查询词——它要的是「我这个身份能用哪些工具」。因此本用例不做检索、不接触
 * {@code CandidateRetriever}，只做授权过滤、投影、排序与预算裁剪。</p>
 *
 * <p><b>投影不放松任何执行期校验。</b>客户端持有 alias 不等于持有执行许可：</p>
 * <ol>
 * <li>{@link #project} 与 {@link #bind} 各自独立重新认证、重新解析策略快照，
 * 不复用会话里缓存的授权结论；</li>
 * <li>alias 反查表是每次调用按「当前授权集合」现场重建的，撤权后 alias 直接不在表内，
 * 与「该 alias 从未存在」返回完全相同的 {@code CAPABILITY_UNAVAILABLE}——
 * 不可枚举语义由此保证；</li>
 * <li>执行凭据 {@code toolRef} 由 {@link ToolReferenceService#issue} 当场签发（短 TTL、
 * 绑定 principal/catalogVersion/policyEpoch），随后写入 {@link AgentTurnStore}，
 * 真正的调用仍然走 {@code AgentHostConnector.call(..., CallPolicy.READ_ONLY)}。</li>
 * </ol>
 *
 * <p>本用例有意<b>不</b>改动 {@link AgentHostConnector}：直投只是多了一条「alias → toolRef」
 * 的前置换算，执行链路一个环节都不绕过，也就不需要给连接器加参数或加依赖（开闭原则）。</p>
 *
 * <p>直投资格是三条硬约束的交集，任一不满足即不投：</p>
 * <ul>
 * <li>{@link RiskLevel#READ_ONLY}——写操作永不直投，维持「仅受信客户端 + 写前置确认」现状；</li>
 * <li>公开投影存在（{@code CapabilityPublicProjectionService} 的注入检测与可信字段剥离已通过）；</li>
 * <li>{@code SchemaClass != COMPLEX} 且公开 Schema 非空——含组合器或过大的 Schema
 * 对通用客户端没有可用价值，仍走 Meta-Tool 路径。</li>
 * </ul>
 *
 * <p>本类无可变状态，线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class AgentToolProjectionUseCase {

    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;
    private final InMemoryCatalogManager catalogManager;
    private final ToolReferenceService toolReferenceService;
    private final AgentTurnStore turnStore;
    private final AliasGenerator aliasGenerator;
    private final CapabilityProjectionRanker ranker;
    private final TelemetryPort telemetry;

    /**
     * 使用默认排序策略（{@link CapabilityProjectionRanker#lexicographic()}）构造用例。
     */
    public AgentToolProjectionUseCase(AuthenticationPort authenticationPort,
                                      AuthorizationPort authorizationPort,
                                      InMemoryCatalogManager catalogManager,
                                      ToolReferenceService toolReferenceService,
                                      AgentTurnStore turnStore,
                                      AliasGenerator aliasGenerator,
                                      TelemetryPort telemetry) {
        this(authenticationPort, authorizationPort, catalogManager, toolReferenceService,
                turnStore, aliasGenerator, CapabilityProjectionRanker.lexicographic(),
                telemetry);
    }

    /**
     * @param ranker 超预算裁剪时的排序策略，决定「先投哪些能力」
     */
    public AgentToolProjectionUseCase(AuthenticationPort authenticationPort,
                                      AuthorizationPort authorizationPort,
                                      InMemoryCatalogManager catalogManager,
                                      ToolReferenceService toolReferenceService,
                                      AgentTurnStore turnStore,
                                      AliasGenerator aliasGenerator,
                                      CapabilityProjectionRanker ranker,
                                      TelemetryPort telemetry) {
        this.authenticationPort = Objects.requireNonNull(
                authenticationPort, "authenticationPort must not be null");
        this.authorizationPort = Objects.requireNonNull(
                authorizationPort, "authorizationPort must not be null");
        this.catalogManager = Objects.requireNonNull(
                catalogManager, "catalogManager must not be null");
        this.toolReferenceService = Objects.requireNonNull(
                toolReferenceService, "toolReferenceService must not be null");
        this.turnStore = Objects.requireNonNull(turnStore, "turnStore must not be null");
        this.aliasGenerator = Objects.requireNonNull(
                aliasGenerator, "aliasGenerator must not be null");
        this.ranker = Objects.requireNonNull(ranker, "ranker must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    /**
     * 投影当前 Principal 已授权且具备直投资格的只读能力清单。
     *
     * <p>超出 {@code budget} 时按 {@link CapabilityProjectionRanker} 的顺序取前 N 个，
     * 并把 {@code degraded} 置为 {@code true}；调用方（协议适配层）据此保留 Meta-Tool 兜底
     * 并记录降级埋点。裁剪只影响「展示」，不影响「可执行性」——被裁掉的能力仍可通过
     * Meta-Tool 路径调用，见 {@link #bind}。</p>
     *
     * @param requestContext 调用方凭据与请求元数据，不能为 {@code null}
     * @param budget         展示预算，不能为 {@code null}
     * @return 投影结果；认证、目录或策略不可用时为 {@link Status#ERROR}
     */
    public ProjectionResult project(RequestContext requestContext, ProjectionBudget budget) {
        Objects.requireNonNull(requestContext, "requestContext must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        Principal principal = authenticate(requestContext);
        if (principal == null) {
            return failedProjection("AUTHENTICATION_FAILED", 0L, 0L);
        }
        ActiveCatalogView.ViewLease lease = catalogManager.acquireActiveView();
        if (lease == null) {
            return failedProjection("CAPABILITY_UNAVAILABLE", 0L, 0L);
        }
        try {
            ActiveCatalogView view = lease.view();
            if (view == null || view.catalogVersion() <= 0) {
                return failedProjection("CAPABILITY_UNAVAILABLE", 0L, 0L);
            }
            PolicySnapshot policySnapshot = policySnapshot(principal);
            if (policySnapshot == null) {
                return failedProjection("POLICY_UNAVAILABLE", view.catalogVersion(), 0L);
            }
            return applyBudget(view, aliasIndex(view, policySnapshot.visibility()),
                    budget, policySnapshot.policyEpoch());
        } finally {
            lease.close();
        }
    }

    /**
     * 把客户端持有的 alias 换成一次性执行凭据，并为本次调用建立一个专用轮次。
     *
     * <p>严格按设计的 {@code tools/call} 顺序执行第 2~4 步：alias 反查 → 按当前 Principal
     * 重新授权 → 现场签发 {@code toolRef} 并写入 {@link AgentTurnState}。第 5 步
     * （{@code AgentHostConnector.call(..., READ_ONLY)}）由协议适配层完成。</p>
     *
     * <p>这里<b>刻意不施加展示预算</b>：{@link #project} 的裁剪是上下文成本控制，
     * 若绑定也按裁剪后的集合查找，一个仍被授权、只是没排进 Top-N 的能力就会突然不可执行——
     * 那是把「展示策略」错误地升级成了「授权策略」。</p>
     *
     * <p>轮次是一次性的：{@code AgentTurnStore.claimTool} 会把首个被取用的 toolRef 钉死在
     * 轮次上，因此每次直投调用都必须落在独立的 {@code agentTurnId} 上，由调用方按 JSON-RPC
     * 请求标识派生。</p>
     *
     * @param requestContext 调用方凭据与请求元数据，不能为 {@code null}
     * @param alias          {@code tools/list} 中暴露的工具名（{@code cap_<hash>}）
     * @param agentTurnId    本次调用专用的轮次标识
     * @param requestId      发起本次绑定的请求标识
     * @return 绑定结果；alias 不存在与已撤权返回同一 {@code CAPABILITY_UNAVAILABLE}
     */
    public BindResult bind(RequestContext requestContext, String alias,
                           String agentTurnId, String requestId) {
        Objects.requireNonNull(requestContext, "requestContext must not be null");
        requireText(alias, "alias");
        requireText(agentTurnId, "agentTurnId");
        requireText(requestId, "requestId");
        Principal principal = authenticate(requestContext);
        if (principal == null) {
            return BindResult.error("AUTHENTICATION_FAILED");
        }
        ActiveCatalogView.ViewLease lease = catalogManager.acquireActiveView();
        if (lease == null) {
            return BindResult.error("CAPABILITY_UNAVAILABLE");
        }
        try {
            ActiveCatalogView view = lease.view();
            if (view == null || view.catalogVersion() <= 0) {
                return BindResult.error("CAPABILITY_UNAVAILABLE");
            }
            PolicySnapshot policySnapshot = policySnapshot(principal);
            if (policySnapshot == null) {
                return BindResult.error("POLICY_UNAVAILABLE");
            }
            CapabilityManifest manifest =
                    aliasIndex(view, policySnapshot.visibility()).get(alias);
            if (manifest == null || !authorized(principal, manifest)) {
                // 撤权、目录变更与「alias 从未存在」在此收敛为同一响应：
                // 任何差异都会把「该身份未被授权的能力是否存在」变成可探测信息。
                telemetry.increment("gateway.agent.projection.bind",
                        Map.of("outcome", "unavailable"));
                return BindResult.error("CAPABILITY_UNAVAILABLE");
            }
            return issue(principal, manifest, view, policySnapshot, alias,
                    agentTurnId, requestId);
        } finally {
            lease.close();
        }
    }

    /** 现场签发执行凭据并建立一次性轮次（设计 §4.4 第 4 步）。 */
    private BindResult issue(Principal principal, CapabilityManifest manifest,
                             ActiveCatalogView view, PolicySnapshot policySnapshot,
                             String alias, String agentTurnId, String requestId) {
        ToolReferenceService.IssuedReference issued = toolReferenceService.issue(
                principal, manifest, view.catalogVersion(), policySnapshot.policyEpoch());
        AgentTurnState state = new AgentTurnState(agentTurnId, requestId,
                view.catalogVersion(), policySnapshot.policyEpoch(), issued.expiresAt(),
                Set.of(issued.toolRef()), null, null, 0, null);
        try {
            turnStore.put(PrincipalFingerprint.digest(principal), state);
        } catch (IllegalStateException e) {
            telemetry.increment("gateway.agent.projection.bind",
                    Map.of("outcome", "turn_capacity_rejected"));
            return BindResult.error("TURN_STATE_CAPACITY_EXCEEDED");
        }
        telemetry.increment("gateway.agent.projection.bind", Map.of("outcome", "bound"));
        return new BindResult(Status.COMPLETED, null, alias, issued.toolRef(),
                agentTurnId, issued.expiresAt(), view.catalogVersion(),
                policySnapshot.policyEpoch());
    }

    /**
     * 构建「alias → 能力」反查表。
     *
     * <p>{@link #project} 与 {@link #bind} 共用同一构建过程，这是 alias 一致性的唯一保证：
     * alias 在碰撞时会按「已分配集合」延长摘要，因此它取决于遍历顺序与集合内容，
     * 两条路径各自实现就可能算出不同的 alias。</p>
     */
    private Map<String, CapabilityManifest> aliasIndex(
            ActiveCatalogView view, CapabilityVisibility visibility) {
        if (visibility == null || !visibility.healthy()) {
            return Map.of();
        }
        List<CapabilityManifest> eligible = view.visibleCapabilities(visibility).stream()
                .filter(Objects::nonNull)
                .filter(manifest -> manifest.spec().risk() == RiskLevel.READ_ONLY)
                .filter(manifest -> directProjection(view, manifest).isPresent())
                .toList();
        Map<String, CapabilityManifest> byAlias = new LinkedHashMap<>();
        Set<String> assigned = new LinkedHashSet<>();
        for (CapabilityManifest manifest : ranker.rank(eligible)) {
            String alias = aliasGenerator.generate(view.catalogVersion(),
                    manifest.metadata().id(), manifest.metadata().version(), assigned);
            assigned.add(alias);
            byAlias.put(alias, manifest);
        }
        return byAlias;
    }

    /** 返回具备直投资格的公开投影：{@code COMPLEX} 与空 Schema 一律不投。 */
    private Optional<CapabilityPublicProjectionService.Projection> directProjection(
            ActiveCatalogView view, CapabilityManifest manifest) {
        return view.publicProjection(manifest)
                .filter(projection -> projection.schemaClass()
                        != CapabilityPublicProjectionService.SchemaClass.COMPLEX)
                .filter(projection -> !projection.publicSchema().isEmpty());
    }

    /** 按预算裁剪投影清单，并给出是否已降级。 */
    private ProjectionResult applyBudget(ActiveCatalogView view,
                                         Map<String, CapabilityManifest> byAlias,
                                         ProjectionBudget budget,
                                         long policyEpoch) {
        List<ProjectedTool> tools = new ArrayList<>(
                Math.min(byAlias.size(), budget.maxTools()));
        long schemaBytes = 0L;
        boolean degraded = false;
        for (Map.Entry<String, CapabilityManifest> entry : byAlias.entrySet()) {
            if (tools.size() >= budget.maxTools()) {
                degraded = true;
                break;
            }
            CapabilityPublicProjectionService.Projection projection =
                    directProjection(view, entry.getValue()).orElse(null);
            if (projection == null) {
                continue;
            }
            ProjectedTool tool = new ProjectedTool(entry.getKey(), projection.displayName(),
                    projection.purpose(), projection.publicSchema());
            long toolBytes = estimatedUtf8Bytes(tool.inputSchema());
            // 首个工具即超字节预算时仍然投出：否则客户端会拿到一份空清单，
            // 既失去可用性又无法从 tools/list 感知到「有能力但太大」。
            if (!tools.isEmpty() && schemaBytes + toolBytes > budget.maxSchemaBytes()) {
                degraded = true;
                break;
            }
            tools.add(tool);
            schemaBytes += toolBytes;
        }
        telemetry.increment("gateway.agent.projection", Map.of(
                "outcome", degraded ? "degraded" : "projected"));
        telemetry.recordValue("gateway.agent.projection.tools", tools.size(),
                Map.of("resource", "direct"));
        return new ProjectionResult(Status.COMPLETED, null, view.catalogVersion(),
                policyEpoch, List.copyOf(tools), byAlias.size(), degraded);
    }

    /** 认证失败一律折叠为 {@code null}，不把底层异常信息带到协议层。 */
    private Principal authenticate(RequestContext requestContext) {
        try {
            return authenticationPort.authenticate(requestContext);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 解析策略快照；不健康或 epoch 非法时返回 {@code null}（fail closed）。 */
    private PolicySnapshot policySnapshot(Principal principal) {
        PolicySnapshot policySnapshot;
        try {
            policySnapshot = authorizationPort.resolvePolicySnapshot(principal);
        } catch (RuntimeException e) {
            return null;
        }
        if (policySnapshot == null || !policySnapshot.healthy()
                || policySnapshot.policyEpoch() <= 0) {
            return null;
        }
        return policySnapshot;
    }

    /**
     * 执行鉴权第 2 趟：alias 反查命中只说明该能力当前对本 Principal 可见，
     * 具体版本能否执行仍需显式判定，异常同样失效关闭。
     */
    private boolean authorized(Principal principal, CapabilityManifest manifest) {
        try {
            return authorizationPort.authorizeExecution(principal,
                    manifest.metadata().id(), manifest.metadata().version());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ProjectionResult failedProjection(
            String errorCode, long catalogVersion, long policyEpoch) {
        telemetry.increment("gateway.agent.projection", Map.of("outcome", "error"));
        return new ProjectionResult(Status.ERROR, errorCode, catalogVersion, policyEpoch,
                List.of(), 0, false);
    }

    private static long estimatedUtf8Bytes(Object value) {
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** 用例结果状态。 */
    public enum Status { COMPLETED, ERROR }

    /**
     * 直投展示预算。
     *
     * @param maxTools       最多直投的工具数
     * @param maxSchemaBytes 所有直投 Schema 的累计字节上限
     */
    public record ProjectionBudget(int maxTools, long maxSchemaBytes) {
        public ProjectionBudget {
            if (maxTools <= 0) {
                throw new IllegalArgumentException("maxTools must be positive");
            }
            if (maxSchemaBytes <= 0) {
                throw new IllegalArgumentException("maxSchemaBytes must be positive");
            }
        }
    }

    /**
     * 一个可直接映射为 MCP 工具定义的模型可见投影。
     *
     * <p>四个字段全部来自已治理的公开投影：{@code alias} 不含真实 {@code capabilityId}，
     * {@code inputSchema} 已剥离所有非 MODEL 来源的可信字段。</p>
     */
    public record ProjectedTool(String alias, String displayName, String purpose,
                                Map<String, Object> inputSchema) {
        public ProjectedTool {
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }
    }

    /**
     * 投影结果。
     *
     * @param eligibleCount 裁剪前具备直投资格的能力总数，供调用方判断降级幅度
     * @param degraded      是否因超出预算而被裁剪，{@code true} 时必须保留 Meta-Tool 兜底
     */
    public record ProjectionResult(Status status, String errorCode, long catalogVersion,
                                   long policyEpoch, List<ProjectedTool> tools,
                                   int eligibleCount, boolean degraded) {
        public ProjectionResult {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    /** alias → 执行凭据的绑定结果。 */
    public record BindResult(Status status, String errorCode, String alias, String toolRef,
                             String agentTurnId, Instant expiresAt, long catalogVersion,
                             long policyEpoch) {
        private static BindResult error(String errorCode) {
            return new BindResult(Status.ERROR, errorCode, null, null, null, null, 0L, 0L);
        }
    }
}
