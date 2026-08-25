package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.RequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 把按会话身份投影的工具面接到 MCP JSON-RPC 上的拦截器实现。
 *
 * <p>它是唯一同时接触 MCP SDK Schema 与 {@link McpGatewayAdapter} 的类，因此 SDK 版本演进
 * 的影响面被限制在这一处。它只拦截两个方法：</p>
 * <ul>
 * <li>{@code tools/list}——用 {@link McpGatewayAdapter#toolsList(RequestContext)} 的
 * 按身份清单作答，替代 SDK 的静态全局清单；</li>
 * <li>{@code tools/call}——仅当工具名不是两个固定 Meta-Tool 时才接手（即 alias 调用）。
 * Meta-Tool 一律交还 SDK，避免出现两条行为可能漂移的等价路径。</li>
 * </ul>
 *
 * <p>alias 调用同样经过 {@link McpCallExecutor}：舱壁容量与截止时间是执行边界的一部分，
 * 若因为换了一条协议入口就绕过它们，直投路径就成了压垮网关的旁路。</p>
 *
 * <p>本类无可变状态，线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class McpSessionToolFacade implements McpSessionRequestInterceptor {

    /** 固定 Meta-Tool 名集合：这些工具仍由 SDK 注册表分发。 */
    private static final Set<String> META_TOOL_NAMES =
            Set.of("gateway_resolve", "gateway_call");

    private final McpGatewayAdapter gatewayAdapter;
    private final ObjectMapper objectMapper;
    private final McpCallExecutor callExecutor;

    /**
     * @param gatewayAdapter 网关桥接器，不能为 {@code null}
     * @param objectMapper   JSON 序列化器，不能为 {@code null}
     * @param callExecutor   调用执行器（舱壁 + 截止时间），不能为 {@code null}
     */
    public McpSessionToolFacade(McpGatewayAdapter gatewayAdapter,
                                ObjectMapper objectMapper,
                                McpCallExecutor callExecutor) {
        this.gatewayAdapter = Objects.requireNonNull(
                gatewayAdapter, "gatewayAdapter must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.callExecutor = Objects.requireNonNull(callExecutor, "callExecutor must not be null");
    }

    @Override
    public Object intercept(String method, Object params, RequestContext context,
                            long deadlineNanos) {
        if (context == null || gatewayAdapter.exposureMode() == McpToolExposureMode.META_TOOL) {
            return null;
        }
        if (McpSchema.METHOD_TOOLS_LIST.equals(method)) {
            return listTools(context);
        }
        if (McpSchema.METHOD_TOOLS_CALL.equals(method)) {
            return callTool(params, context, deadlineNanos);
        }
        return null;
    }

    /** 用按身份投影的清单作答；序列化失败的单个工具被跳过而非让整个清单失败。 */
    private McpSchema.ListToolsResult listTools(RequestContext context) {
        List<McpSchema.Tool> sdkTools = new ArrayList<>();
        for (McpMetaToolCatalog.McpTool tool : gatewayAdapter.toolsList(context)) {
            try {
                sdkTools.add(new McpSchema.Tool(tool.name(), tool.description(),
                        objectMapper.writeValueAsString(tool.inputSchema())));
            } catch (JsonProcessingException e) {
                // 一个能力的 Schema 序列化失败不应让客户端拿不到任何工具。
                continue;
            }
        }
        return new McpSchema.ListToolsResult(List.copyOf(sdkTools), null);
    }

    /**
     * 处理 alias 形态的 {@code tools/call}。
     *
     * <p>返回 {@code null} 的两种情形都表示「交还 SDK」：参数无法解析（让 SDK 给出标准的
     * 协议错误），或工具名是固定 Meta-Tool。</p>
     */
    private McpSchema.CallToolResult callTool(Object params, RequestContext context,
                                              long deadlineNanos) {
        McpSchema.CallToolRequest request;
        try {
            request = objectMapper.convertValue(params, McpSchema.CallToolRequest.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (request == null || request.name() == null
                || META_TOOL_NAMES.contains(request.name())) {
            return null;
        }
        Map<String, Object> arguments = request.arguments() == null
                ? Map.of() : request.arguments();
        try {
            return callExecutor.execute(
                            () -> invoke(request.name(), context, arguments), deadlineNanos)
                    .block();
        } catch (RuntimeException e) {
            return errorResult(errorCodeOf(e));
        }
    }

    private McpSchema.CallToolResult invoke(String alias, RequestContext context,
                                            Map<String, Object> arguments) {
        McpGatewayAdapter.McpResult result = gatewayAdapter.invoke(alias, context,
                McpRequestKeys.requestId(context, alias, arguments), arguments);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status());
        payload.putAll(result.content());
        try {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(payload))
                    .isError("ERROR".equals(result.status()))
                    .build();
        } catch (JsonProcessingException e) {
            return errorResult("SERIALIZATION_FAILED");
        }
    }

    /** 只把已知的资源类失败翻译为稳定错误码，其余一律归为通用失败，不透传内部异常文本。 */
    private static String errorCodeOf(RuntimeException e) {
        Throwable cause = reactor.core.Exceptions.unwrap(e);
        if (cause instanceof TimeoutException) {
            return "MCP_CALL_TIMEOUT";
        }
        if (cause instanceof RejectedExecutionException) {
            return "MCP_CALL_CAPACITY_EXCEEDED";
        }
        return "MCP_CALL_FAILED";
    }

    private static McpSchema.CallToolResult errorResult(String errorCode) {
        return McpSchema.CallToolResult.builder()
                .addTextContent("{\"status\":\"ERROR\",\"errorCode\":\"" + errorCode + "\"}")
                .isError(true)
                .build();
    }
}
