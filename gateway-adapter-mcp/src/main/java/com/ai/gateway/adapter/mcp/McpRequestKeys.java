package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 生成不依赖随机 UUID 的 MCP 请求键。
 */
final class McpRequestKeys {

    private McpRequestKeys() {
    }

    static String requestId(RequestContext context, String toolName,
                            Map<String, Object> arguments) {
        return "mcp-" + digest(material(context, toolName, arguments));
    }

    static String idempotencyKey(RequestContext context, String toolName,
                                 Map<String, Object> arguments) {
        return "mcp-" + digest(material(context, toolName, arguments));
    }

    private static String material(RequestContext context, String toolName,
                                   Map<String, Object> arguments) {
        String sessionId = context.header("Mcp-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = context.queryParams().get("sessionId");
        }
        return (sessionId == null ? "direct" : sessionId) + "\n"
                + toolName + "\n" + canonical(arguments);
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, String> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted.toString();
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            list.forEach(item -> values.add(canonical(item)));
            return values.toString();
        }
        return String.valueOf(value);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
