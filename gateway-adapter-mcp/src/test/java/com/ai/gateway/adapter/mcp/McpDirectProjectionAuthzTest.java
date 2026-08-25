package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper;
import com.ai.gateway.application.agent.AgentToolProjectionUseCase;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Acceptance tests for the direct-projection {@code tools/call} path (design §4.4).
 *
 * <p>The point of these tests is that direct projection relaxes nothing: the alias is
 * re-authorized on every call, every unavailability reason collapses into one
 * indistinguishable error code, execution still crosses the single
 * {@link AgentHostConnector} boundary with {@code READ_ONLY} and {@link AuditPlane#MCP},
 * and no execution credential leaks back to the model.</p>
 *
 * @author cmiracle@163.com
 */
class McpDirectProjectionAuthzTest {

    @Test
    void unknownAliasIsRejectedWithoutEverReachingTheExecutionBoundary() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.bind(any(), eq("cap_unknown"), anyString(), anyString()))
                .thenReturn(error("CAPABILITY_UNAVAILABLE"));
        McpGatewayAdapter adapter = adapter(connector, useCase);

        McpGatewayAdapter.McpResult result = adapter.invoke("cap_unknown",
                RequestContext.empty(), "request-1", Map.of());

        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.content()).containsEntry("errorCode", "CAPABILITY_UNAVAILABLE");
        assertThat(result.content()).containsEntry("message", "Capability is not available");
        verifyNoInteractions(connector);
    }

    @Test
    void revokedCapabilityIsIndistinguishableFromAnAliasThatNeverExisted() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.bind(any(), eq("cap_unknown"), anyString(), anyString()))
                .thenReturn(error("CAPABILITY_UNAVAILABLE"));
        when(useCase.bind(any(), eq("cap_revoked"), anyString(), anyString()))
                .thenReturn(error("CAPABILITY_UNAVAILABLE"));
        McpGatewayAdapter adapter = adapter(mock(AgentHostConnector.class), useCase);

        McpGatewayAdapter.McpResult unknown = adapter.invoke("cap_unknown",
                RequestContext.empty(), "request-1", Map.of());
        McpGatewayAdapter.McpResult revoked = adapter.invoke("cap_revoked",
                RequestContext.empty(), "request-2", Map.of());

        assertThat(revoked.status()).isEqualTo(unknown.status());
        assertThat(revoked.content().get("errorCode"))
                .isEqualTo(unknown.content().get("errorCode"));
        assertThat(revoked.content().get("message"))
                .isEqualTo(unknown.content().get("message"));
    }

    @Test
    void metaToolOnlyDeploymentTreatsAnAliasAsAnUnknownToolAndNeverBinds() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        AgentHostConnector connector = mock(AgentHostConnector.class);
        McpGatewayAdapter adapter = new McpGatewayAdapter(connector,
                McpSecurityMode.READ_ONLY, McpClientTrustRegistry.disabled(),
                McpRateLimiter.allowAll(), McpProjectedToolCatalog.metaToolOnly());

        McpGatewayAdapter.McpResult result = adapter.invoke("cap_aaa",
                RequestContext.empty(), "request-1", Map.of());

        assertThat(result.content()).containsEntry("errorCode", "UNKNOWN_TOOL");
        verifyNoInteractions(useCase);
        verifyNoInteractions(connector);
    }

    @Test
    void projectedCallCrossesTheSameExecutionBoundaryAsReadOnlyOnThePlaneMcp() {
        AgentHostConnector connector = completingConnector();
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.bind(any(), eq("cap_aaa"), anyString(), anyString()))
                .thenReturn(bound("cap_aaa", "tool-ref-1", "proj:request-1"));
        McpGatewayAdapter adapter = adapter(connector, useCase);

        McpGatewayAdapter.McpResult result = adapter.invoke("cap_aaa",
                localized("en-US,zh-CN;q=0.8"), "request-1", Map.of("orderNo", "A-1"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        // 入参原样透传：tools/list 公布的就是能力自己的 Schema，这里不得再套一层信封。
        verify(connector).call(any(), anyString(), eq("request-1"), eq("tool-ref-1"),
                eq(Map.of("orderNo", "A-1")), eq("en-US"), eq("request-1"),
                eq(AgentHostConnector.CallPolicy.READ_ONLY), eq(AuditPlane.MCP));
    }

    @Test
    void unusableAcceptLanguageFallsBackToTheDefaultInsteadOfFailingTheCall() {
        AgentHostConnector connector = completingConnector();
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.bind(any(), eq("cap_aaa"), anyString(), anyString()))
                .thenReturn(bound("cap_aaa", "tool-ref-1", "proj:request-1"));
        McpGatewayAdapter adapter = adapter(connector, useCase);

        adapter.invoke("cap_aaa", localized("*"), "request-1", Map.of());

        verify(connector).call(any(), anyString(), anyString(), anyString(), anyMap(),
                eq("zh-CN"), anyString(), any(AgentHostConnector.CallPolicy.class),
                any(AuditPlane.class));
    }

    @Test
    void aCapabilityFieldNamedToolRefIsAnArgumentAndNeverAnExecutionCredential() {
        AgentHostConnector connector = completingConnector();
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.bind(any(), eq("cap_aaa"), anyString(), anyString()))
                .thenReturn(bound("cap_aaa", "issued-tool-ref", "proj:request-1"));
        McpGatewayAdapter adapter = adapter(connector, useCase);

        adapter.invoke("cap_aaa", RequestContext.empty(), "request-1",
                Map.of("toolRef", "smuggled-tool-ref", "orderNo", "A-1"));

        // 模型填的 toolRef 只是一个同名入参，执行凭据永远是本次现场签发的那个。
        verify(connector).call(any(), anyString(), anyString(), eq("issued-tool-ref"),
                eq(Map.of("toolRef", "smuggled-tool-ref", "orderNo", "A-1")),
                anyString(), anyString(), eq(AgentHostConnector.CallPolicy.READ_ONLY),
                eq(AuditPlane.MCP));
    }

    @Test
    void everyProjectedCallGetsItsOwnTurnBecauseTheStorePinsTheFirstClaimedToolRef() {
        AgentHostConnector connector = completingConnector();
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.bind(any(), eq("cap_aaa"), anyString(), anyString()))
                .thenAnswer(invocation -> bound("cap_aaa", "tool-ref",
                        invocation.getArgument(2)));
        McpGatewayAdapter adapter = adapter(connector, useCase);

        adapter.invoke("cap_aaa", RequestContext.empty(), "request-1", Map.of());
        adapter.invoke("cap_aaa", RequestContext.empty(), "request-2", Map.of());

        ArgumentCaptor<String> turnIds = ArgumentCaptor.forClass(String.class);
        verify(useCase, org.mockito.Mockito.times(2))
                .bind(any(), eq("cap_aaa"), turnIds.capture(), anyString());
        assertThat(turnIds.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void projectedResultNeverCarriesTheIssuedToolRefBackToTheModel() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(), any(AgentHostConnector.CallPolicy.class),
                any(AuditPlane.class)))
                .thenReturn(new AgentHostConnector.CallResult(
                        new AgentModelResultMapper.ModelResult(
                                AgentModelResultMapper.ModelResult.Status.COMPLETED,
                                Map.of("orderNo", "A-1"), null, null, null, null),
                        null, "private-confirmation-token"));
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.bind(any(), eq("cap_aaa"), anyString(), anyString()))
                .thenReturn(bound("cap_aaa", "secret-tool-ref", "proj:request-1"));
        McpGatewayAdapter adapter = adapter(connector, useCase);

        McpGatewayAdapter.McpResult result = adapter.invoke("cap_aaa",
                RequestContext.empty(), "request-1", Map.of());

        assertThat(result.content().toString())
                .doesNotContain("secret-tool-ref")
                .doesNotContain("private-confirmation-token");
    }

    @Test
    void projectedCallIsRateLimitedBeforeAnyBinding() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        AgentHostConnector connector = mock(AgentHostConnector.class);
        McpGatewayAdapter adapter = new McpGatewayAdapter(connector,
                McpSecurityMode.READ_ONLY, McpClientTrustRegistry.disabled(),
                McpRateLimiter.from(new com.ai.gateway.application.resilience
                        .RateLimiterManager((dimension, key, permits) -> false)),
                catalog(useCase));

        McpGatewayAdapter.McpResult result = adapter.invoke("cap_aaa",
                RequestContext.empty(), "request-1", Map.of());

        assertThat(result.content()).containsEntry("errorCode", "MCP_RATE_LIMITED");
        verify(useCase, never()).bind(any(), anyString(), anyString(), anyString());
        verifyNoInteractions(connector);
    }

    private static AgentHostConnector completingConnector() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString(), any(AgentHostConnector.CallPolicy.class),
                any(AuditPlane.class)))
                .thenReturn(new AgentHostConnector.CallResult(
                        new AgentModelResultMapper.ModelResult(
                                AgentModelResultMapper.ModelResult.Status.COMPLETED,
                                Map.of("orderNo", "A-1"), null, null, null, null),
                        null, null));
        return connector;
    }

    private static RequestContext localized(String acceptLanguage) {
        return new RequestContext(Map.of("Accept-Language", acceptLanguage),
                Map.of(), Map.of(), null);
    }

    private static McpGatewayAdapter adapter(AgentHostConnector connector,
                                             AgentToolProjectionUseCase useCase) {
        return new McpGatewayAdapter(connector, McpSecurityMode.READ_ONLY,
                McpClientTrustRegistry.disabled(), McpRateLimiter.allowAll(),
                catalog(useCase));
    }

    private static McpProjectedToolCatalog catalog(AgentToolProjectionUseCase useCase) {
        return McpProjectedToolCatalog.of(McpToolExposureMode.HYBRID, useCase,
                new AgentToolProjectionUseCase.ProjectionBudget(64, 131_072L),
                mock(TelemetryPort.class));
    }

    private static AgentToolProjectionUseCase.BindResult bound(
            String alias, String toolRef, String agentTurnId) {
        return new AgentToolProjectionUseCase.BindResult(
                AgentToolProjectionUseCase.Status.COMPLETED, null, alias, toolRef,
                agentTurnId, Instant.now().plusSeconds(120L), 9L, 5L);
    }

    private static AgentToolProjectionUseCase.BindResult error(String errorCode) {
        return new AgentToolProjectionUseCase.BindResult(
                AgentToolProjectionUseCase.Status.ERROR, errorCode, null, null, null,
                null, 0L, 0L);
    }
}
