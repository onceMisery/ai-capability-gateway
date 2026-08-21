package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper;
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
    private final McpSecurityMode securityMode;
    private final McpClientTrustRegistry trustRegistry;
    private final McpRateLimiter rateLimiter;

    /**
     * 构造一个新的 McpGatewayAdapter。
     *
     * @param connector 与 Agent 应用交互的连接器，不能为 {@code null}
     */
    public McpGatewayAdapter(AgentHostConnector connector) {
        this(connector, McpSecurityMode.READ_ONLY,
                McpClientTrustRegistry.disabled(), McpRateLimiter.allowAll());
    }

    public McpGatewayAdapter(AgentHostConnector connector, McpSecurityMode securityMode) {
        this(connector, securityMode, McpClientTrustRegistry.disabled(),
                McpRateLimiter.allowAll());
    }

    public McpGatewayAdapter(AgentHostConnector connector,
                             McpSecurityMode securityMode,
                             McpClientTrustRegistry trustRegistry,
                             McpRateLimiter rateLimiter) {
        this.connector = Objects.requireNonNull(connector);
        this.securityMode = Objects.requireNonNull(securityMode);
        this.trustRegistry = Objects.requireNonNull(trustRegistry);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        if (securityMode == McpSecurityMode.DISABLED) {
            throw new IllegalArgumentException("MCP adapter must not be constructed in DISABLED mode");
        }
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
        if (toolName == null || toolName.isBlank()
                || requestId == null || requestId.isBlank()) {
            return McpResult.error("INVALID_ARGUMENTS", "tool name is required");
        }
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
        String invalid = validateKeys(arguments, "query", "agentTurnId", "topK", "locale");
        if (invalid != null || queryText.length() > 4096) {
            return McpResult.error("INVALID_ARGUMENTS", invalid == null
                    ? "query is too long" : invalid);
        }
        if (arguments.containsKey("topK") && !(arguments.get("topK") instanceof Number)) {
            return McpResult.error("INVALID_ARGUMENTS", "topK must be an integer");
        }
        int topK = number(arguments.get("topK"), 5);
        if (topK < 1 || topK > 5) {
            return McpResult.error("INVALID_ARGUMENTS", "topK must be between 1 and 5");
        }
        String invalidText = validateOptionalText(arguments, "agentTurnId");
        if (invalidText == null) {
            invalidText = validateOptionalText(arguments, "locale");
        }
        if (invalidText != null) {
            return McpResult.error("INVALID_ARGUMENTS", invalidText);
        }
        if (!rateLimiter.tryAcquire(McpRateLimiter.RESOLVE)) {
            return McpResult.error("MCP_RATE_LIMITED", "MCP resolve rate limit reached");
        }
        String agentTurnId = text(arguments.get("agentTurnId"), requestId);
        String locale = text(arguments.get("locale"), "zh-CN");
        if (agentTurnId.length() > 128 || locale.length() > 32) {
            return McpResult.error("INVALID_ARGUMENTS", "argument length exceeds limit");
        }
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
                || toolRef.isBlank()
                || !(arguments.get("arguments") instanceof Map<?, ?> rawArguments)) {
            return McpResult.error("INVALID_ARGUMENTS", "toolRef and arguments are required");
        }
        String invalid = validateKeys(arguments, "toolRef", "agentTurnId", "arguments",
                "locale", "idempotencyKey");
        if (invalid != null || toolRef.length() > 2048) {
            return McpResult.error("INVALID_ARGUMENTS", invalid == null
                    ? "toolRef is too long" : invalid);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> modelArguments = (Map<String, Object>) rawArguments;
        String invalidText = validateOptionalText(arguments, "agentTurnId");
        if (invalidText == null) {
            invalidText = validateOptionalText(arguments, "locale");
        }
        if (invalidText == null) {
            invalidText = validateOptionalText(arguments, "idempotencyKey");
        }
        if (invalidText != null) {
            return McpResult.error("INVALID_ARGUMENTS", invalidText);
        }
        if (!rateLimiter.tryAcquire(McpRateLimiter.CALL)) {
            return McpResult.error("MCP_RATE_LIMITED", "MCP call rate limit reached");
        }
        String locale = text(arguments.get("locale"), "zh-CN");
        if (locale.length() > 32) {
            return McpResult.error("INVALID_ARGUMENTS", "locale is too long");
        }
        String idempotencyKey = text(arguments.get("idempotencyKey"), requestId);
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            return McpResult.error("INVALID_ARGUMENTS", "idempotencyKey length is invalid");
        }
        String agentTurnId = text(arguments.get("agentTurnId"), requestId);
        if (agentTurnId.length() > 128) {
            return McpResult.error("INVALID_ARGUMENTS", "agentTurnId is too long");
        }
        boolean trustedClient = securityMode.allowWritePrepare()
                && trustRegistry.isTrusted(context);
        AgentHostConnector.CallResult hostResult = connector.call(
                context, scopedTurnId(context, agentTurnId), requestId, toolRef,
                modelArguments, locale, idempotencyKey,
                trustedClient
                        ? AgentHostConnector.CallPolicy.HOST_CONFIRMATION
                        : AgentHostConnector.CallPolicy.READ_ONLY);
        var safe = hostResult.result();
        return McpResult.of(safe.status().name(), Map.of(
                "requestId", requestId,
                "agentTurnId", agentTurnId,
                "data", safe.data() == null ? Map.of() : safe.data(),
                "errorCode", safe.errorCode() == null ? "" : safe.errorCode(),
                "message", safeMessage(safe),
                "operationId", safe.operationId() == null ? "" : safe.operationId(),
                "expiresAt", safe.expiresAt() == null ? "" : safe.expiresAt()));
    }

    /**
     * 返回数值参数，缺省值只用于未提供参数；非法值由调用方拒绝。
     */
    private static int number(Object value, int defaultValue) {
        if (!(value instanceof Number number)) {
            return defaultValue;
        }
        return number.intValue();
    }

    /**
     * 返回非空文本参数，否则回退到默认值。
     */
    private static String text(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static String validateOptionalText(Map<String, Object> arguments, String key) {
        if (!arguments.containsKey(key)) {
            return null;
        }
        Object value = arguments.get(key);
        if (!(value instanceof String)) {
            return key + " must be a string";
        }
        if (((String) value).isBlank()) {
            return key + " must not be blank";
        }
        return null;
    }

    private static String validateKeys(Map<String, Object> arguments, String... allowed) {
        java.util.Set<String> allowedSet = java.util.Set.of(allowed);
        for (String key : arguments.keySet()) {
            if (!allowedSet.contains(key)) {
                return "unknown argument: " + key;
            }
        }
        return null;
    }

    private static String safeMessage(AgentModelResultMapper.ModelResult result) {
        return switch (result.status()) {
            case COMPLETED -> "Completed";
            case CONFIRMATION_REQUIRED -> "User confirmation required";
            case ERROR -> "Request failed";
        };
    }

    /**
     * 将请求中的 sessionId 与 turnId 组合为作用域化的轮次标识。
     *
     * <p>优先取 {@code Mcp-Session-Id} 请求头，其次兼容查询参数
     * {@code sessionId}；二者皆缺省时以 {@code direct} 标识直连调用。</p>
     */
    private static String scopedTurnId(RequestContext context, String turnId) {
        String sessionId = context.header("Mcp-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = context.queryParams().get("sessionId");
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
