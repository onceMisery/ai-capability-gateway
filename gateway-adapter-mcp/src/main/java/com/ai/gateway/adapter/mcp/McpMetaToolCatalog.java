package com.ai.gateway.adapter.mcp;

import java.util.List;
import java.util.Map;

/** Fixed MCP tool list; capability manifests are never mirrored into tools/list. */
public final class McpMetaToolCatalog {

    private McpMetaToolCatalog() {
    }

    public static List<McpTool> tools() {
        return List.of(
                new McpTool("gateway_resolve",
                        "Discover a small authorized set of gateway capabilities",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string", "maxLength", 4096),
                                        "agentTurnId", Map.of("type", "string", "maxLength", 128),
                                        "topK", Map.of("type", "integer", "minimum", 1,
                                                "maximum", 5),
                                        "locale", Map.of("type", "string", "maxLength", 32)),
                                "required", List.of("query"),
                                "additionalProperties", false)),
                new McpTool("gateway_call",
                        "Call one capability returned for the current gateway turn",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "toolRef", Map.of("type", "string", "maxLength", 2048),
                                        "agentTurnId", Map.of("type", "string", "maxLength", 128),
                                        "arguments", Map.of("type", "object"),
                                        "locale", Map.of("type", "string", "maxLength", 32),
                                        "idempotencyKey", Map.of("type", "string",
                                                "maxLength", 256)),
                                "required", List.of("toolRef", "arguments", "locale"),
                                "additionalProperties", false)));
    }

    public record McpTool(String name, String description, Map<String, Object> inputSchema) {
    }
}
