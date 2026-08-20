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
import java.util.UUID;
import java.time.Duration;
import reactor.core.publisher.Mono;

/** Real WebMVC MCP transport with a fixed, model-safe tool surface. */
public final class McpWebMvcTransportAdapter {

    private final ObjectMapper objectMapper;
    private final AuthenticatedWebMvcSseServerTransportProvider transportProvider;
    private final McpAsyncServer server;

    public McpWebMvcTransportAdapter(ObjectMapper objectMapper,
                                     McpGatewayAdapter gatewayAdapter,
                                     AuthenticationPort authenticationPort,
                                     TelemetryPort telemetry,
                                     int maxSessions,
                                     Duration idleTimeout) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        Objects.requireNonNull(gatewayAdapter);
        this.transportProvider = new AuthenticatedWebMvcSseServerTransportProvider(
                objectMapper, authenticationPort, telemetry,
                "/mcp/message", "/mcp/sse", maxSessions, idleTimeout);
        McpServer.AsyncSpecification specification = McpServer.async(transportProvider)
                .serverInfo("ai-capability-gateway", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        for (McpMetaToolCatalog.McpTool tool : gatewayAdapter.toolsList()) {
            McpSchema.Tool sdkTool = sdkTool(tool);
            specification.tool(sdkTool, (exchange, arguments) -> Mono.deferContextual(
                    context -> Mono.just(invoke(gatewayAdapter, tool.name(),
                            McpRequestContextHolder.current(context), arguments))));
        }
        this.server = specification.build();
    }

    public RouterFunction<ServerResponse> routerFunction() {
        return transportProvider.getRouterFunction();
    }

    public McpAsyncServer server() {
        return server;
    }

    public AuthenticatedWebMvcSseServerTransportProvider transportProvider() {
        return transportProvider;
    }

    private McpSchema.CallToolResult invoke(McpGatewayAdapter gatewayAdapter,
                                            String toolName,
                                            RequestContext context,
                                            Map<String, Object> arguments) {
        McpGatewayAdapter.McpResult result = gatewayAdapter.invoke(
                toolName, context, UUID.randomUUID().toString(),
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

    private McpSchema.Tool sdkTool(McpMetaToolCatalog.McpTool tool) {
        try {
            return new McpSchema.Tool(tool.name(), tool.description(),
                    objectMapper.writeValueAsString(tool.inputSchema()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("MCP tool schema serialization failed: " + tool.name(), e);
        }
    }
}
