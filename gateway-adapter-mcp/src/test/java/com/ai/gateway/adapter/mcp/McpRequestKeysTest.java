package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.RequestContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpRequestKeysTest {

    @Test
    void canonicalizesArgumentMapOrderForStableRetryKeys() {
        RequestContext context = new RequestContext(
                Map.of("Authorization", "Bearer test"),
                Map.of(),
                Map.of("sessionId", "session-a"),
                null);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("arguments", Map.of("orderNo", "SO-1", "region", "east"));
        first.put("toolRef", "ref-1");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("toolRef", "ref-1");
        second.put("arguments", Map.of("region", "east", "orderNo", "SO-1"));

        assertThat(McpRequestKeys.requestId(context, "gateway_call", first))
                .isEqualTo(McpRequestKeys.requestId(context, "gateway_call", second));
        assertThat(McpRequestKeys.idempotencyKey(context, "gateway_call", first))
                .isEqualTo(McpRequestKeys.idempotencyKey(context, "gateway_call", second));
    }

    @Test
    void separatesKeysBySessionAndTool() {
        RequestContext sessionA = new RequestContext(
                Map.of(), Map.of(), Map.of("sessionId", "session-a"), null);
        RequestContext sessionB = new RequestContext(
                Map.of(), Map.of(), Map.of("sessionId", "session-b"), null);

        String first = McpRequestKeys.requestId(sessionA, "gateway_call", Map.of("x", 1));
        String otherSession = McpRequestKeys.requestId(sessionB, "gateway_call", Map.of("x", 1));
        String otherTool = McpRequestKeys.requestId(sessionA, "gateway_resolve", Map.of("x", 1));

        assertThat(first).isNotEqualTo(otherSession);
        assertThat(first).isNotEqualTo(otherTool);
    }
}
