package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.agent.AgentToolProjectionUseCase;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.TelemetryPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按会话身份组装 {@code tools/list} 内容，并把 alias 换算为执行凭据。
 *
 * <p>它是 MCP 协议侧与 {@link AgentToolProjectionUseCase} 之间唯一的胶合层，存在的理由
 * 是让 {@link McpGatewayAdapter} 只多出<b>一个</b>协作者而不是四个（模式、用例、预算、
 * 遥测）。曝光策略、降级判定与 {@code mcp.} 前缀埋点都收敛在这里，
 * {@link McpGatewayAdapter} 与 {@code gateway-application} 都不需要因为新增一种曝光模式
 * 而修改（开闭原则）。</p>
 *
 * <p><b>降级是单向的：只会把工具面变得更可用，不会变得更不可用。</b>三种情形都会补回
 * Meta-Tool 并记录 {@code gateway.mcp.projection.degraded}：</p>
 * <ol>
 * <li>投影失败（认证、目录或策略不可用）——此时一个真实工具都投不出来，若不补回
 * Meta-Tool，客户端会拿到空清单而彻底不可用；</li>
 * <li>投影被预算裁剪（{@code degraded=true}）——被裁掉的能力仍然被授权，必须留下
 * {@code gateway_resolve} 这条可达路径；</li>
 * <li>{@code DIRECT_PROJECTION} 模式下出现上述任一情形——模式偏好让位于可用性。</li>
 * </ol>
 *
 * <p>注意补回 Meta-Tool <b>不是</b>安全降级：Meta-Tool 路径与直投路径共用同一条执行边界
 * （{@code AgentHostConnector}）与同一次执行期鉴权，两者的权限完全一致。</p>
 *
 * <p>本类无可变状态，线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class McpProjectedToolCatalog {

    /** 直投工具名禁止占用的保留前缀，避免 alias 与 Meta-Tool 名冲突。 */
    private static final String RESERVED_PREFIX = "gateway_";

    private final McpToolExposureMode mode;
    private final AgentToolProjectionUseCase projectionUseCase;
    private final AgentToolProjectionUseCase.ProjectionBudget budget;
    private final TelemetryPort telemetry;

    private McpProjectedToolCatalog(McpToolExposureMode mode,
                                    AgentToolProjectionUseCase projectionUseCase,
                                    AgentToolProjectionUseCase.ProjectionBudget budget,
                                    TelemetryPort telemetry) {
        this.mode = mode;
        this.projectionUseCase = projectionUseCase;
        this.budget = budget;
        this.telemetry = telemetry;
    }

    /**
     * 返回只暴露 Meta-Tool 的目录，不需要任何投影依赖。
     *
     * <p>这是缺省装配与向后兼容构造函数使用的形态：未显式开启直投的部署，行为与改造前
     * 完全一致。</p>
     *
     * @return 固定两工具面的目录
     */
    public static McpProjectedToolCatalog metaToolOnly() {
        return new McpProjectedToolCatalog(McpToolExposureMode.META_TOOL, null, null, null);
    }

    /**
     * 返回按给定模式工作的目录。
     *
     * @param mode              曝光模式；{@link McpToolExposureMode#META_TOOL} 等价于
     *                          {@link #metaToolOnly()}
     * @param projectionUseCase 能力直投用例，不能为 {@code null}
     * @param budget            展示预算，不能为 {@code null}
     * @param telemetry         遥测端口，不能为 {@code null}
     * @return 目录实例
     */
    public static McpProjectedToolCatalog of(McpToolExposureMode mode,
                                             AgentToolProjectionUseCase projectionUseCase,
                                             AgentToolProjectionUseCase.ProjectionBudget budget,
                                             TelemetryPort telemetry) {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(projectionUseCase, "projectionUseCase must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        Objects.requireNonNull(telemetry, "telemetry must not be null");
        return new McpProjectedToolCatalog(mode, projectionUseCase, budget, telemetry);
    }

    /** @return 当前曝光模式 */
    public McpToolExposureMode mode() {
        return mode;
    }

    /**
     * 是否可以把未知工具名当作 alias 去反查。
     *
     * <p>{@code META_TOOL} 模式返回 {@code false}，未知工具名一律 {@code UNKNOWN_TOOL}；
     * 否则未知名先按 alias 尝试绑定，失败再回落到 {@code UNKNOWN_TOOL}。</p>
     *
     * @return 是否启用 alias 分发
     */
    public boolean supportsAliasDispatch() {
        return mode.projectsCapabilities() && projectionUseCase != null;
    }

    /**
     * 组装该请求身份可见的 {@code tools/list} 内容。
     *
     * @param context 请求上下文，不能为 {@code null}
     * @return 工具清单，永不为空列表（最差情形退回 Meta-Tool）
     */
    public List<McpMetaToolCatalog.McpTool> tools(RequestContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (!supportsAliasDispatch()) {
            return McpMetaToolCatalog.tools();
        }
        AgentToolProjectionUseCase.ProjectionResult result =
                projectionUseCase.project(context, budget);
        if (result.status() != AgentToolProjectionUseCase.Status.COMPLETED) {
            // 投影失败不能让客户端拿到空清单：Meta-Tool 路径的权限与直投完全一致，
            // 退回它既不放松安全，也保住了可用性。
            recordDegraded("projection_failed");
            return McpMetaToolCatalog.tools();
        }
        List<McpMetaToolCatalog.McpTool> tools = new ArrayList<>(result.tools().size() + 2);
        for (AgentToolProjectionUseCase.ProjectedTool projected : result.tools()) {
            if (projected.alias() == null || projected.alias().startsWith(RESERVED_PREFIX)) {
                continue;
            }
            tools.add(new McpMetaToolCatalog.McpTool(projected.alias(),
                    description(projected), projected.inputSchema()));
        }
        if (result.degraded()) {
            recordDegraded("budget_exceeded");
        }
        if (tools.isEmpty() || result.degraded() || mode.retainsMetaTools()) {
            tools.addAll(McpMetaToolCatalog.tools());
        }
        telemetry.recordValue("gateway.mcp.projection.tools", tools.size(),
                Map.of("resource", "tools_list"));
        return List.copyOf(tools);
    }

    /**
     * 把客户端持有的 alias 换算为一次性执行凭据。
     *
     * <p>不做任何缓存：alias 反查表由用例按「当前授权集合」现场重建，因此撤权在下一次
     * {@code tools/call} 即刻生效——失效不依赖 {@code tools/list_changed} 的送达，
     * 也不存在需要清理的 alias 缓存（fail closed，见
     * {@link McpToolListChangeBroadcaster}）。</p>
     *
     * @param context     请求上下文
     * @param alias       工具名
     * @param agentTurnId 本次调用专用轮次标识
     * @param requestId   请求标识
     * @return 绑定结果；未启用直投时返回 {@code null}
     */
    public AgentToolProjectionUseCase.BindResult bind(RequestContext context, String alias,
                                                      String agentTurnId, String requestId) {
        if (!supportsAliasDispatch()) {
            return null;
        }
        return projectionUseCase.bind(context, alias, agentTurnId, requestId);
    }

    /** 工具描述优先取用途说明，缺省回落到展示名——两者都已通过注入检测。 */
    private static String description(AgentToolProjectionUseCase.ProjectedTool projected) {
        if (projected.purpose() != null && !projected.purpose().isBlank()) {
            return projected.purpose();
        }
        return projected.displayName() == null ? projected.alias() : projected.displayName();
    }

    private void recordDegraded(String reason) {
        telemetry.increment("gateway.mcp.projection.degraded",
                Map.of("reason", reason, "mode", mode.name()));
    }
}
