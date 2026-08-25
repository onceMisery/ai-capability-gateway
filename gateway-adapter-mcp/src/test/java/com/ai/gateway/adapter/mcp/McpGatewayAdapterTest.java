package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper;
import com.ai.gateway.application.agent.AgentToolProjectionUseCase;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpGatewayAdapter} 的单元测试，验证固定 Meta-Tool 列表、调用结果不泄露确认令牌、
 * 会话 ID 参与连接器轮次键拼接，以及开启直投后两条工具面仍收敛到同一执行边界（设计 §4.8）。
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
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(AgentHostConnector.CallPolicy.class),
                any(AuditPlane.class)))
                .thenReturn(new AgentHostConnector.CallResult(
                        new AgentModelResultMapper.ModelResult(
                                AgentModelResultMapper.ModelResult.Status.CONFIRMATION_REQUIRED,
                                null, null, "confirm", "op", null),
                        null, "private-token"));
        String token = "trusted-token";
        McpClientTrustRegistry registry = new McpClientTrustRegistry(List.of(
                new McpClientTrustProfile("host",
                        McpClientTrustRegistry.sha256(token),
                        McpClientTrustProfile.TokenAssurance.HIGH,
                        McpClientTrustProfile.ConfirmationChannel.HOST_UI,
                        true, Instant.now().plusSeconds(3_600L))));
        McpGatewayAdapter adapter = new McpGatewayAdapter(
                connector, McpSecurityMode.TRUSTED_CONFIRMATION, registry,
                McpRateLimiter.allowAll());

        McpGatewayAdapter.McpResult result = adapter.invoke("gateway_call",
                new RequestContext(Map.of("Authorization", "Bearer " + token),
                        Map.of(), Map.of(), null), "req-1", Map.of(
                "toolRef", "tr", "arguments", Map.of(), "locale", "zh-CN",
                "agentTurnId", "turn-1"));

        assertThat(result.content().toString()).doesNotContain("private-token");
        assertThat(result.status()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(result.content().get("message")).isEqualTo("User confirmation required");
        verify(connector).call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(AgentHostConnector.CallPolicy.HOST_CONFIRMATION),
                eq(AuditPlane.MCP));
    }

    @Test
    void trustedConfirmationModeFallsBackToReadOnlyForUnregisteredClient() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(AgentHostConnector.CallPolicy.class),
                any(AuditPlane.class)))
                .thenReturn(new AgentHostConnector.CallResult(
                        new AgentModelResultMapper.ModelResult(
                                AgentModelResultMapper.ModelResult.Status.ERROR, null,
                                "MCP_WRITE_DISABLED", "ignored", null, null),
                        null, null));
        McpGatewayAdapter adapter = new McpGatewayAdapter(
                connector, McpSecurityMode.TRUSTED_CONFIRMATION,
                McpClientTrustRegistry.disabled(), McpRateLimiter.allowAll());

        adapter.invoke("gateway_call",
                new RequestContext(Map.of("Authorization", "Bearer unknown"),
                        Map.of(), Map.of(), null), "request-1",
                Map.of("toolRef", "ref", "arguments", Map.of(), "locale", "zh-CN"));

        verify(connector).call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(AgentHostConnector.CallPolicy.READ_ONLY),
                eq(AuditPlane.MCP));
    }

    @Test
    void sessionIdIsPartOfTheConnectorTurnKey() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(AgentHostConnector.CallPolicy.class),
                any(AuditPlane.class)))
                .thenReturn(new AgentHostConnector.CallResult(
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

        verify(connector).call(any(), org.mockito.ArgumentMatchers.eq("ignored-header:turn-1"),
                anyString(), org.mockito.ArgumentMatchers.eq("ref"), anyMap(),
                org.mockito.ArgumentMatchers.eq("zh-CN"), anyString(),
                org.mockito.ArgumentMatchers.any(AgentHostConnector.CallPolicy.class),
                eq(AuditPlane.MCP));
    }

    @Test
    void defaultsToReadOnlyAndPassesReadOnlyPolicyToConnector() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(AgentHostConnector.CallPolicy.class),
                any(AuditPlane.class)))
                .thenReturn(new AgentHostConnector.CallResult(
                new AgentModelResultMapper.ModelResult(
                        AgentModelResultMapper.ModelResult.Status.ERROR, null,
                        "MCP_WRITE_DISABLED", "ignored", null, null),
                null, null));

        McpGatewayAdapter adapter = new McpGatewayAdapter(connector);
        adapter.invoke("gateway_call", RequestContext.empty(), "request-1",
                Map.of("toolRef", "ref", "arguments", Map.of(), "locale", "zh-CN"));

        verify(connector).call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(AgentHostConnector.CallPolicy.READ_ONLY),
                eq(AuditPlane.MCP));
    }

    @Test
    void rejectsInvalidArgumentsInsteadOfSilentlyClamping() {
        McpGatewayAdapter adapter = new McpGatewayAdapter(mock(AgentHostConnector.class));

        McpGatewayAdapter.McpResult result = adapter.invoke(
                "gateway_resolve", RequestContext.empty(), "request-1",
                Map.of("query", "orders", "topK", 0));

        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.content()).containsEntry("errorCode", "INVALID_ARGUMENTS");
    }

    @Test
    void rejectsWrongArgumentTypesInsteadOfApplyingDefaults() {
        McpGatewayAdapter adapter = new McpGatewayAdapter(mock(AgentHostConnector.class));

        McpGatewayAdapter.McpResult result = adapter.invoke(
                "gateway_resolve", RequestContext.empty(), "request-1",
                Map.of("query", "orders", "topK", "5"));

        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.content()).containsEntry("errorCode", "INVALID_ARGUMENTS");
    }

    @Test
    void rejectsIdempotencyKeyLongerThanBackendContract() {
        McpGatewayAdapter adapter = new McpGatewayAdapter(mock(AgentHostConnector.class));

        McpGatewayAdapter.McpResult result = adapter.invoke(
                "gateway_call", RequestContext.empty(), "request-1",
                Map.of("toolRef", "ref", "arguments", Map.of(),
                        "idempotencyKey", "x".repeat(129)));

        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.content()).containsEntry("errorCode", "INVALID_ARGUMENTS");
    }

    @Test
    void aliasDispatchStaysOffForTheExistingConstructorsSoDeploymentsAreUnchanged() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        McpGatewayAdapter adapter = new McpGatewayAdapter(connector);

        // 直投是新增的可选工具面，既有构造出的适配器行为必须与改造前逐字一致。
        assertThat(adapter.exposureMode()).isEqualTo(McpToolExposureMode.META_TOOL);
        assertThat(adapter.toolsList(RequestContext.empty()))
                .extracting(McpMetaToolCatalog.McpTool::name)
                .containsExactly("gateway_resolve", "gateway_call");
        assertThat(adapter.invoke("cap_aaa", RequestContext.empty(), "request-1",
                Map.of()).content()).containsEntry("errorCode", "UNKNOWN_TOOL");
        org.mockito.Mockito.verifyNoInteractions(connector);
    }

    @Test
    void projectedAndMetaToolCallsShareOneBoundaryAndOneModelVisibleResultShape() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(AgentHostConnector.CallPolicy.class),
                any(AuditPlane.class)))
                .thenReturn(new AgentHostConnector.CallResult(
                        new AgentModelResultMapper.ModelResult(
                                AgentModelResultMapper.ModelResult.Status.COMPLETED,
                                Map.of("orderNo", "A-1"), null, null, null, null),
                        null, "private-token"));
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.bind(any(), eq("cap_aaa"), anyString(), anyString()))
                .thenReturn(new AgentToolProjectionUseCase.BindResult(
                        AgentToolProjectionUseCase.Status.COMPLETED, null, "cap_aaa",
                        "issued-ref", "proj:request-2", Instant.now().plusSeconds(120L),
                        9L, 5L));
        McpGatewayAdapter adapter = new McpGatewayAdapter(connector,
                McpSecurityMode.READ_ONLY, McpClientTrustRegistry.disabled(),
                McpRateLimiter.allowAll(),
                McpProjectedToolCatalog.of(McpToolExposureMode.HYBRID, useCase,
                        new AgentToolProjectionUseCase.ProjectionBudget(64, 131_072L),
                        mock(TelemetryPort.class)));

        McpGatewayAdapter.McpResult metaTool = adapter.invoke("gateway_call",
                RequestContext.empty(), "request-1",
                Map.of("toolRef", "ref", "arguments", Map.of("orderNo", "A-1")));
        McpGatewayAdapter.McpResult projected = adapter.invoke("cap_aaa",
                RequestContext.empty(), "request-2", Map.of("orderNo", "A-1"));

        // 两条工具面共用同一次执行期鉴权与同一份结果投影：字段集合一旦分叉，
        // 直投就成了独立的第二条执行路径，安全结论也不再可迁移。
        assertThat(projected.status()).isEqualTo(metaTool.status());
        assertThat(projected.content().keySet()).isEqualTo(metaTool.content().keySet());
        assertThat(projected.content().toString()).doesNotContain("private-token")
                .doesNotContain("issued-ref");
        verify(connector, org.mockito.Mockito.times(2)).call(any(), anyString(),
                anyString(), anyString(), anyMap(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(AgentHostConnector.CallPolicy.READ_ONLY),
                eq(AuditPlane.MCP));
    }
}
