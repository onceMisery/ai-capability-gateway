package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.domain.model.RequestContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 与传输层无关的 MCP 桥接器，仅面向两个固定的 Meta-Tool。
 *
 * <p>MCP SDK/传输适配器可调用本类；JSON-RPC 生命周期、会话建立与身份认证仍由该传输适配器负责。
 * 本类有意不暴露 Confirm、Cancel、Status 或完整的能力目录。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class McpGatewayAdapter {

    private final AgentHostConnector connector;

    /**
     * 构造一个新的 McpGatewayAdapter。
     *
     * @param connector 与 Agent 应用交互的连接器，不能为 {@code null}
     */
    public McpGatewayAdapter(AgentHostConnector connector) {
        this.connector = Objects.requireNonNull(connector);
    }

    /**
     * 返回当前暴露的固定 Meta-Tool 列表。
     *
     * @return 仅包含 {@code gateway_resolve} 与 {@code gateway_call} 的工具清单
     */
    public List<McpMetaToolCatalog.McpTool> toolsList() {
        return McpMetaToolCatalog.tools();
    }

    /**
     * 按工具名分发调用到对应的固定 Meta-Tool。
     *
     * <p>仅支持 {@code gateway_resolve} 与 {@code gateway_call}；未知工具名返回
     * {@code UNKNOWN_TOOL} 错误结果。</p>
     *
     * @param toolName   工具名
     * @param context    请求上下文
     * @param requestId  请求标识
     * @param arguments  工具参数
     * @return MCP 调用结果
     */
    public McpResult invoke(String toolName,
                            RequestContext context,
                            String requestId,
                            Map<String, Object> arguments) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        return switch (toolName) {
            case "gateway_resolve" -> resolve(context, requestId, arguments);
            case "gateway_call" -> call(context, requestId, arguments);
            default -> McpResult.error("UNKNOWN_TOOL", "Unknown MCP Meta-Tool");
        };
    }

    /**
     * 处理 {@code gateway_resolve}：根据查询文本发现一小批已授权的能力候选。
     */
    private McpResult resolve(RequestContext context, String requestId,
                              Map<String, Object> arguments) {
        Object query = arguments.get("query");
        if (!(query instanceof String queryText) || queryText.isBlank()) {
            return McpResult.error("INVALID_ARGUMENTS", "query is required");
        }
        // topK 限定在 [1,5] 区间，缺省为 5
        int topK = number(arguments.get("topK"), 5);
        String agentTurnId = text(arguments.get("agentTurnId"), requestId);
        // 缺省语言区域为 zh-CN
        String locale = arguments.get("locale") instanceof String value ? value : "zh-CN";
        AgentHostConnector.ResolveResult hostResult = connector.resolve(
                context, scopedTurnId(context, agentTurnId), requestId, queryText, topK);
        var result = hostResult.resolution();
        return McpResult.of(result.status().name(), Map.of(
                "requestId", requestId,
                "agentTurnId", agentTurnId,
                "catalogVersion", result.catalogVersion(),
                "policyEpoch", result.policyEpoch(),
                "candidates", result.candidates(),
                "selectedSchema", result.selectedSchema() == null
                        ? Map.of() : result.selectedSchema(),
                "locale", locale,
                "errorCode", result.errorCode() == null ? "" : result.errorCode()));
    }

    /**
     * 处理 {@code gateway_call}：调用当前网关轮次返回的某个能力。
     */
    private McpResult call(RequestContext context, String requestId,
                           Map<String, Object> arguments) {
        if (!(arguments.get("toolRef") instanceof String toolRef)
                || !(arguments.get("arguments") instanceof Map<?, ?> rawArguments)) {
            return McpResult.error("INVALID_ARGUMENTS", "toolRef and arguments are required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> modelArguments = (Map<String, Object>) rawArguments;
        String locale = arguments.get("locale") instanceof String value ? value : "zh-CN";
        // 幂等键缺省回退为 requestId，避免重复执行
        String idempotencyKey = arguments.get("idempotencyKey") instanceof String value
                ? value : requestId;
        String agentTurnId = text(arguments.get("agentTurnId"), requestId);
        AgentHostConnector.CallResult hostResult = connector.call(
                context, scopedTurnId(context, agentTurnId), requestId, toolRef,
                modelArguments, locale, idempotencyKey);
        var safe = hostResult.result();
        return McpResult.of(safe.status().name(), Map.of(
                "requestId", requestId,
                "agentTurnId", agentTurnId,
                "data", safe.data() == null ? Map.of() : safe.data(),
                "errorCode", safe.errorCode() == null ? "" : safe.errorCode(),
                "message", safe.message() == null ? "" : safe.message(),
                "operationId", safe.operationId() == null ? "" : safe.operationId(),
                "expiresAt", safe.expiresAt() == null ? "" : safe.expiresAt()));
    }

    /**
     * 将数值参数约束在 [1,5] 区间，非数字时返回默认值。
     */
    private static int number(Object value, int defaultValue) {
        if (!(value instanceof Number number)) {
            return defaultValue;
        }
        return Math.max(1, Math.min(5, number.intValue()));
    }

    /**
     * 返回非空文本参数，否则回退到默认值。
     */
    private static String text(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    /**
     * 将请求中的 sessionId 与 turnId 组合为作用域化的轮次标识。
     *
     * <p>优先取查询参数 {@code sessionId}，其次取 {@code Mcp-Session-Id} 请求头；
     * 二者皆缺省时以 {@code direct} 标识直连调用。</p>
     */
    private static String scopedTurnId(RequestContext context, String turnId) {
        String sessionId = context.queryParams().get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = context.header("Mcp-Session-Id");
        }
        return (sessionId == null || sessionId.isBlank() ? "direct" : sessionId)
                + ":" + turnId;
    }

    /**
     * MCP 调用结果封装：状态与内容负载的不可变记录。
     *
     * @param status  结果状态（如 {@code SUCCESS}、{@code ERROR}）
     * @param content 结果内容负载
     */
    public record McpResult(String status, Map<String, Object> content) {
        static McpResult of(String status, Map<String, Object> content) {
            return new McpResult(status, Map.copyOf(content));
        }

        static McpResult error(String code, String message) {
            return new McpResult("ERROR", Map.of("errorCode", code, "message", message));
        }
    }
}
