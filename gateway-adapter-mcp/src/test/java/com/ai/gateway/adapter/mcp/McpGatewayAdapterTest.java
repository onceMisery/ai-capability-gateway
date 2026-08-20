package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper;
import com.ai.gateway.domain.model.RequestContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpGatewayAdapter} 的单元测试，验证固定 Meta-Tool 列表、调用结果不泄露确认令牌，
 * 以及会话 ID 参与连接器轮次键拼接。
 *
 * @author cmiracle@163.com
 */
class McpGatewayAdapterTest {

    @Test
    void toolsListContainsOnlyFixedMetaTools() {
        assertThat(McpMetaToolCatalog.tools()).extracting(McpMetaToolCatalog.McpTool::name)
                .containsExactly("gateway_resolve", "gateway_call");
    }

    @Test
    void writeResultDoesNotExposeConfirmationToken() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString())).thenReturn(new AgentHostConnector.CallResult(
                        new AgentModelResultMapper.ModelResult(
                                AgentModelResultMapper.ModelResult.Status.CONFIRMATION_REQUIRED,
                                null, null, "confirm", "op", null),
                        null, "private-token"));
        McpGatewayAdapter adapter = new McpGatewayAdapter(connector);

        McpGatewayAdapter.McpResult result = adapter.invoke("gateway_call",
                RequestContext.empty(), "req-1", Map.of(
                "toolRef", "tr", "arguments", Map.of(), "locale", "zh-CN",
                "agentTurnId", "turn-1"));

        assertThat(result.content().toString()).doesNotContain("private-token");
        assertThat(result.status()).isEqualTo("CONFIRMATION_REQUIRED");
    }

    @Test
    void sessionIdIsPartOfTheConnectorTurnKey() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString())).thenReturn(new AgentHostConnector.CallResult(
                        new AgentModelResultMapper.ModelResult(
                                AgentModelResultMapper.ModelResult.Status.ERROR, null,
                                "TOOL_REF_NOT_IN_TURN", "rejected", null, null),
                        null, null));
        McpGatewayAdapter adapter = new McpGatewayAdapter(connector);
        adapter.invoke("gateway_call",
                new com.ai.gateway.domain.model.RequestContext(
                        Map.of("Mcp-Session-Id", "ignored-header"), Map.of(),
                        Map.of("sessionId", "session-a"), null),
                "request-1", Map.of("agentTurnId", "turn-1", "toolRef", "ref",
                        "arguments", Map.of(), "locale", "zh-CN"));

        verify(connector).call(any(), org.mockito.ArgumentMatchers.eq("session-a:turn-1"),
                anyString(), org.mockito.ArgumentMatchers.eq("ref"), anyMap(),
                org.mockito.ArgumentMatchers.eq("zh-CN"), anyString());
    }
}
