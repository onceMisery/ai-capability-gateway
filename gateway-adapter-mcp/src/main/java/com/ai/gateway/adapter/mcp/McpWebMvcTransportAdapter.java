package com.ai.gateway.adapter.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.time.Duration;
import reactor.core.publisher.Mono;

/**
 * 真实的 WebMVC MCP 传输适配器，提供固定且对模型安全的工具面。
 *
 * <p>基于 Spring WebMVC 的 SSE 传输实现，将固定的 Meta-Tool 注册到 MCP 服务，并在每次工具调用时
 * 从 Reactor 上下文中取回请求上下文后委派给 {@link McpGatewayAdapter}。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class McpWebMvcTransportAdapter {

    private final ObjectMapper objectMapper;
    private final AuthenticatedWebMvcSseServerTransportProvider transportProvider;
    private final McpAsyncServer server;

    /**
     * 构造传输适配器并构建 MCP 服务。
     *
     * <p>内部创建带认证的 SSE 传输提供者（消息端点 {@code /mcp/message}、SSE 端点
     * {@code /mcp/sse}），并为每个固定 Meta-Tool 注册调用处理。</p>
     *
     * @param objectMapper         JSON 序列化器
     * @param gatewayAdapter       网关桥接器
     * @param authenticationPort   认证端口
     * @param telemetry           遥测端口
     * @param maxSessions         最大并发会话数
     * @param idleTimeout         会话空闲超时
     */
    public McpWebMvcTransportAdapter(ObjectMapper objectMapper,
                                     McpGatewayAdapter gatewayAdapter,
                                     AuthenticationPort authenticationPort,
                                     TelemetryPort telemetry,
                                     int maxSessions,
                                     Duration idleTimeout) {
        this(objectMapper, gatewayAdapter, authenticationPort, telemetry, maxSessions,
                idleTimeout, Duration.ofSeconds(30), Duration.ofSeconds(5), "local",
                McpRateLimiter.allowAll(), McpCallExecutor.direct());
    }

    public McpWebMvcTransportAdapter(ObjectMapper objectMapper,
                                     McpGatewayAdapter gatewayAdapter,
                                     AuthenticationPort authenticationPort,
                                     TelemetryPort telemetry,
                                     int maxSessions,
                                     Duration idleTimeout,
                                     Duration callTimeout) {
        this(objectMapper, gatewayAdapter, authenticationPort, telemetry, maxSessions,
                idleTimeout, callTimeout, Duration.ofSeconds(5), "local",
                McpRateLimiter.allowAll(), McpCallExecutor.direct());
    }

    public McpWebMvcTransportAdapter(ObjectMapper objectMapper,
                                     McpGatewayAdapter gatewayAdapter,
                                     AuthenticationPort authenticationPort,
                                     TelemetryPort telemetry,
                                     int maxSessions,
                                     Duration idleTimeout,
                                     Duration callTimeout,
                                     Duration closeTimeout,
                                     String nodeId,
                                     McpRateLimiter rateLimiter) {
        this(objectMapper, gatewayAdapter, authenticationPort, telemetry, maxSessions,
                idleTimeout, callTimeout, closeTimeout, nodeId, rateLimiter,
                McpCallExecutor.direct());
    }

    public McpWebMvcTransportAdapter(ObjectMapper objectMapper,
                                     McpGatewayAdapter gatewayAdapter,
                                     AuthenticationPort authenticationPort,
                                     TelemetryPort telemetry,
                                     int maxSessions,
                                     Duration idleTimeout,
                                     Duration callTimeout,
                                     Duration closeTimeout,
                                     String nodeId,
                                     McpRateLimiter rateLimiter,
                                     McpCallExecutor callExecutor) {
        this(objectMapper, gatewayAdapter,
                McpRequestAuthenticator.bearer(authenticationPort), telemetry,
                maxSessions, idleTimeout, callTimeout, closeTimeout, nodeId,
                rateLimiter, callExecutor);
    }

    public McpWebMvcTransportAdapter(ObjectMapper objectMapper,
                                     McpGatewayAdapter gatewayAdapter,
                                     McpRequestAuthenticator requestAuthenticator,
                                     TelemetryPort telemetry,
                                     int maxSessions,
                                     Duration idleTimeout,
                                     Duration callTimeout,
                                     Duration closeTimeout,
                                     String nodeId,
                                     McpRateLimiter rateLimiter,
                                     McpCallExecutor callExecutor) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        Objects.requireNonNull(gatewayAdapter);
        Objects.requireNonNull(callExecutor);
        // SDK 的高层服务只能静态注册工具，因此注册面永远是两个 Meta-Tool；
        // 按会话身份投影的工具面由拦截器在 JSON-RPC 层覆盖 tools/list 与 alias 形态的
        // tools/call。两者分工明确：静态注册保证协议握手可用，拦截器负责身份相关部分。
        this.transportProvider = new AuthenticatedWebMvcSseServerTransportProvider(
                objectMapper, requestAuthenticator, telemetry,
                "/mcp/message", "/mcp/sse", maxSessions, idleTimeout, callTimeout,
                closeTimeout, nodeId, rateLimiter,
                new McpSessionToolFacade(gatewayAdapter, objectMapper, callExecutor));
        McpServer.AsyncSpecification specification = McpServer.async(transportProvider)
                .serverInfo("ai-capability-gateway", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        List<McpMetaToolCatalog.McpTool> tools = gatewayAdapter.toolsList();
        assertFixedTools(tools);
        for (McpMetaToolCatalog.McpTool tool : tools) {
            McpSchema.Tool sdkTool = sdkTool(tool);
            specification.tool(sdkTool, (exchange, arguments) -> Mono.deferContextual(
                    context -> callExecutor.execute(
                                    () -> invoke(gatewayAdapter, tool.name(),
                                            McpRequestContextHolder.forAgentHost(context), arguments),
                                    McpRequestContextHolder.deadlineNanos(context))
                            .onErrorResume(TimeoutException.class,
                                    ignored -> Mono.just(errorResult("MCP_CALL_TIMEOUT")))
                            .onErrorResume(RejectedExecutionException.class,
                                    ignored -> Mono.just(errorResult("MCP_CALL_CAPACITY_EXCEEDED")))));
        }
        this.server = specification.build();
    }

    private McpSchema.CallToolResult errorResult(String errorCode) {
        return McpSchema.CallToolResult.builder()
                .addTextContent("{\"status\":\"ERROR\",\"errorCode\":\""
                        + errorCode + "\"}")
                .isError(true)
                .build();
    }

    /**
     * 返回用于挂载到 Spring WebMVC 的路由函数。
     */
    public RouterFunction<ServerResponse> routerFunction() {
        return transportProvider.getRouterFunction();
    }

    /**
     * 返回已构建的异步 MCP 服务实例。
     */
    public McpAsyncServer server() {
        return server;
    }

    /**
     * 返回底层带认证的 SSE 传输提供者。
     */
    public AuthenticatedWebMvcSseServerTransportProvider transportProvider() {
        return transportProvider;
    }

    /**
     * 调用网关桥接器并将结果封装为 MCP 的 CallToolResult。
     *
     * <p>若结果状态为 {@code ERROR} 则标记 {@code isError}；序列化失败时返回
     * {@code SERIALIZATION_FAILED} 错误结果。</p>
     */
    private McpSchema.CallToolResult invoke(McpGatewayAdapter gatewayAdapter,
                                            String toolName,
                                            RequestContext context,
                                            Map<String, Object> arguments) {
        McpGatewayAdapter.McpResult result = gatewayAdapter.invoke(
                toolName, context, McpRequestKeys.requestId(context, toolName,
                        arguments == null ? Map.of() : arguments),
                arguments == null ? Map.of() : arguments);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status());
        payload.putAll(result.content());
        try {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(payload))
                    .isError("ERROR".equals(result.status()))
                    .build();
        } catch (JsonProcessingException e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("{\"status\":\"ERROR\",\"errorCode\":\"SERIALIZATION_FAILED\"}")
                    .isError(true)
                    .build();
        }
    }

    /**
     * 将固定 Meta-Tool 转换为 MCP SDK 的 Tool 定义（含 JSON schema）。
     */
    private McpSchema.Tool sdkTool(McpMetaToolCatalog.McpTool tool) {
        try {
            return new McpSchema.Tool(tool.name(), tool.description(),
                    objectMapper.writeValueAsString(tool.inputSchema()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("MCP tool schema serialization failed: " + tool.name(), e);
        }
    }

    private static void assertFixedTools(List<McpMetaToolCatalog.McpTool> tools) {
        if (tools == null || tools.size() != 2
                || !"gateway_resolve".equals(tools.get(0).name())
                || !"gateway_call".equals(tools.get(1).name())) {
            throw new IllegalStateException(
                    "MCP must expose exactly gateway_resolve and gateway_call");
        }
    }
}
