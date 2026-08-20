package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.domain.model.RequestContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-neutral MCP bridge for the two fixed Meta-Tools.
 *
 * <p>An MCP SDK/transport adapter can call this class; JSON-RPC lifecycle,
 * session setup and authentication remain owned by that transport adapter.
 * This class intentionally does not expose Confirm, Cancel, Status, or the
 * full Capability catalog.</p>
 */
public final class McpGatewayAdapter {

    private final AgentHostConnector connector;

    public McpGatewayAdapter(AgentHostConnector connector) {
        this.connector = Objects.requireNonNull(connector);
    }

    public List<McpMetaToolCatalog.McpTool> toolsList() {
        return McpMetaToolCatalog.tools();
    }

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

    private McpResult resolve(RequestContext context, String requestId,
                              Map<String, Object> arguments) {
        Object query = arguments.get("query");
        if (!(query instanceof String queryText) || queryText.isBlank()) {
            return McpResult.error("INVALID_ARGUMENTS", "query is required");
        }
        int topK = number(arguments.get("topK"), 5);
        String agentTurnId = text(arguments.get("agentTurnId"), requestId);
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

    private McpResult call(RequestContext context, String requestId,
                           Map<String, Object> arguments) {
        if (!(arguments.get("toolRef") instanceof String toolRef)
                || !(arguments.get("arguments") instanceof Map<?, ?> rawArguments)) {
            return McpResult.error("INVALID_ARGUMENTS", "toolRef and arguments are required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> modelArguments = (Map<String, Object>) rawArguments;
        String locale = arguments.get("locale") instanceof String value ? value : "zh-CN";
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

    private static int number(Object value, int defaultValue) {
        if (!(value instanceof Number number)) {
            return defaultValue;
        }
        return Math.max(1, Math.min(5, number.intValue()));
    }

    private static String text(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static String scopedTurnId(RequestContext context, String turnId) {
        String sessionId = context.queryParams().get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = context.header("Mcp-Session-Id");
        }
        return (sessionId == null || sessionId.isBlank() ? "direct" : sessionId)
                + ":" + turnId;
    }

    public record McpResult(String status, Map<String, Object> content) {
        static McpResult of(String status, Map<String, Object> content) {
            return new McpResult(status, Map.copyOf(content));
        }

        static McpResult error(String code, String message) {
            return new McpResult("ERROR", Map.of("errorCode", code, "message", message));
        }
    }
}
