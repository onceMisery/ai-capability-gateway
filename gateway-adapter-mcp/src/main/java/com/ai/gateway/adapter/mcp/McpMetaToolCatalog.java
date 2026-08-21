package com.ai.gateway.adapter.mcp;

import java.util.List;
import java.util.Map;

/**
 * 固定的 MCP 工具清单；能力清单（capability manifest）绝不会被镜像进 tools/list。
 *
 * <p>仅暴露两个 Meta-Tool：{@code gateway_resolve} 用于发现已授权能力，
 * {@code gateway_call} 用于调用当前网关轮次返回的能力。能力清单本身通过 Agent 应用获取，
 * 而非直接映射为 MCP 工具，以避免向模型暴露完整能力目录。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class McpMetaToolCatalog {

    private McpMetaToolCatalog() {
    }

    /**
     * 返回固定的两个 Meta-Tool 定义。
     *
     * <p>工具描述（description）作为 MCP 协议内容直接展示给模型，故保留英文原文；
     * 其余参数 schema 用于约束模型调用入参。</p>
     *
     * @return 包含两个固定 Meta-Tool 的不可变列表
     */
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
                                                "maxLength", 128)),
                                "required", List.of("toolRef", "arguments"),
                                "additionalProperties", false)));
    }

    /**
     * MCP 工具定义记录：名称、描述与输入 schema。
     *
     * @param name        工具名
     * @param description 工具描述（展示给模型）
     * @param inputSchema 输入参数 JSON schema
     */
    public record McpTool(String name, String description, Map<String, Object> inputSchema) {
    }
}
