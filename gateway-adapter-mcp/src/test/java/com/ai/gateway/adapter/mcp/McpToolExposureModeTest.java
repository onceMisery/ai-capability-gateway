package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.agent.AgentToolProjectionUseCase;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.TelemetryPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Acceptance tests for the {@code tools/list} exposure modes (design §4.2, §4.5).
 *
 * <p>Two properties matter more than the individual mode semantics: the list is never
 * empty, and every degradation only ever makes the tool face more usable — adding the
 * meta tools back is not a security downgrade because both faces share one execution
 * boundary and one execution-time authorization.</p>
 *
 * @author cmiracle@163.com
 */
class McpToolExposureModeTest {

    private static final AgentToolProjectionUseCase.ProjectionBudget BUDGET =
            new AgentToolProjectionUseCase.ProjectionBudget(64, 131_072L);
    private static final List<String> META_TOOLS =
            List.of("gateway_resolve", "gateway_call");

    @Test
    void modePredicatesDescribeExactlyOneDisplayDecisionEach() {
        assertThat(McpToolExposureMode.META_TOOL.projectsCapabilities()).isFalse();
        assertThat(McpToolExposureMode.META_TOOL.retainsMetaTools()).isTrue();
        assertThat(McpToolExposureMode.DIRECT_PROJECTION.projectsCapabilities()).isTrue();
        assertThat(McpToolExposureMode.DIRECT_PROJECTION.retainsMetaTools()).isFalse();
        assertThat(McpToolExposureMode.HYBRID.projectsCapabilities()).isTrue();
        assertThat(McpToolExposureMode.HYBRID.retainsMetaTools()).isTrue();
    }

    @Test
    void metaToolOnlyCatalogNeverConsultsProjectionAndDisablesAliasDispatch() {
        McpProjectedToolCatalog catalog = McpProjectedToolCatalog.metaToolOnly();

        assertThat(names(catalog.tools(RequestContext.empty()))).isEqualTo(META_TOOLS);
        assertThat(catalog.supportsAliasDispatch()).isFalse();
        assertThat(catalog.bind(RequestContext.empty(), "cap_x", "turn-1", "req-1")).isNull();
    }

    @Test
    void hybridExposesProjectedCapabilitiesAlongsideMetaTools() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        when(useCase.project(any(), eq(BUDGET))).thenReturn(completed(false,
                tool("cap_aaa", "Order detail", "Query one order")));
        McpProjectedToolCatalog catalog = McpProjectedToolCatalog.of(
                McpToolExposureMode.HYBRID, useCase, BUDGET, telemetry);

        List<McpMetaToolCatalog.McpTool> tools = catalog.tools(RequestContext.empty());

        assertThat(names(tools)).containsExactly("cap_aaa", "gateway_resolve", "gateway_call");
        assertThat(tools.get(0).description()).isEqualTo("Query one order");
        assertThat(catalog.supportsAliasDispatch()).isTrue();
    }

    @Test
    void directProjectionDropsMetaToolsWhileTheProjectionIsHealthy() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.project(any(), eq(BUDGET))).thenReturn(completed(false,
                tool("cap_aaa", "Order detail", "Query one order")));
        McpProjectedToolCatalog catalog = McpProjectedToolCatalog.of(
                McpToolExposureMode.DIRECT_PROJECTION, useCase, BUDGET,
                mock(TelemetryPort.class));

        assertThat(names(catalog.tools(RequestContext.empty()))).containsExactly("cap_aaa");
    }

    @Test
    void failedProjectionFallsBackToMetaToolsInsteadOfAnEmptyToolFace() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        when(useCase.project(any(), eq(BUDGET))).thenReturn(
                new AgentToolProjectionUseCase.ProjectionResult(
                        AgentToolProjectionUseCase.Status.ERROR, "CAPABILITY_UNAVAILABLE",
                        0L, 0L, List.of(), 0, false));
        McpProjectedToolCatalog catalog = McpProjectedToolCatalog.of(
                McpToolExposureMode.DIRECT_PROJECTION, useCase, BUDGET, telemetry);

        assertThat(names(catalog.tools(RequestContext.empty()))).isEqualTo(META_TOOLS);
        verify(telemetry).increment("gateway.mcp.projection.degraded",
                Map.of("reason", "projection_failed",
                        "mode", McpToolExposureMode.DIRECT_PROJECTION.name()));
    }

    @Test
    void truncatedProjectionKeepsMetaToolsAsTheEscapeHatchEvenInDirectMode() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        when(useCase.project(any(), eq(BUDGET))).thenReturn(completed(true,
                tool("cap_aaa", "Order detail", "Query one order")));
        McpProjectedToolCatalog catalog = McpProjectedToolCatalog.of(
                McpToolExposureMode.DIRECT_PROJECTION, useCase, BUDGET, telemetry);

        // 被裁掉的能力仍然被授权，必须留下 gateway_resolve 这条可达路径。
        assertThat(names(catalog.tools(RequestContext.empty())))
                .containsExactly("cap_aaa", "gateway_resolve", "gateway_call");
        verify(telemetry).increment("gateway.mcp.projection.degraded",
                Map.of("reason", "budget_exceeded",
                        "mode", McpToolExposureMode.DIRECT_PROJECTION.name()));
    }

    @Test
    void projectedToolsMayNotOccupyTheReservedMetaToolPrefix() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.project(any(), eq(BUDGET))).thenReturn(completed(false,
                tool("gateway_call", "Impersonator", "Shadows the meta tool"),
                tool("cap_aaa", "Order detail", "Query one order")));
        McpProjectedToolCatalog catalog = McpProjectedToolCatalog.of(
                McpToolExposureMode.DIRECT_PROJECTION, useCase, BUDGET,
                mock(TelemetryPort.class));

        List<McpMetaToolCatalog.McpTool> tools = catalog.tools(RequestContext.empty());

        assertThat(names(tools)).containsExactly("cap_aaa");
        assertThat(tools).noneMatch(tool -> "Impersonator".equals(tool.description()));
    }

    @Test
    void emptyProjectionStillYieldsAUsableToolFace() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.project(any(), eq(BUDGET))).thenReturn(completed(false));
        McpProjectedToolCatalog catalog = McpProjectedToolCatalog.of(
                McpToolExposureMode.DIRECT_PROJECTION, useCase, BUDGET,
                mock(TelemetryPort.class));

        assertThat(names(catalog.tools(RequestContext.empty()))).isEqualTo(META_TOOLS);
    }

    @Test
    void bindIsDelegatedVerbatimSoNoAliasIsResolvedInsideTheProtocolLayer() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        AgentToolProjectionUseCase.BindResult expected =
                new AgentToolProjectionUseCase.BindResult(
                        AgentToolProjectionUseCase.Status.COMPLETED, null, "cap_aaa",
                        "tool-ref", "turn-1", java.time.Instant.now(), 9L, 5L);
        when(useCase.bind(any(), eq("cap_aaa"), eq("turn-1"), eq("req-1")))
                .thenReturn(expected);
        McpProjectedToolCatalog catalog = McpProjectedToolCatalog.of(
                McpToolExposureMode.HYBRID, useCase, BUDGET, mock(TelemetryPort.class));

        assertThat(catalog.bind(RequestContext.empty(), "cap_aaa", "turn-1", "req-1"))
                .isSameAs(expected);
    }

    @Test
    void metaToolOnlyModeIgnoresAnyInjectedProjectionUseCase() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        McpProjectedToolCatalog catalog = McpProjectedToolCatalog.of(
                McpToolExposureMode.META_TOOL, useCase, BUDGET, mock(TelemetryPort.class));

        assertThat(names(catalog.tools(RequestContext.empty()))).isEqualTo(META_TOOLS);
        assertThat(catalog.supportsAliasDispatch()).isFalse();
        verifyNoInteractions(useCase);
    }

    private static AgentToolProjectionUseCase.ProjectionResult completed(
            boolean degraded, AgentToolProjectionUseCase.ProjectedTool... tools) {
        return new AgentToolProjectionUseCase.ProjectionResult(
                AgentToolProjectionUseCase.Status.COMPLETED, null, 9L, 5L,
                List.of(tools), tools.length + (degraded ? 1 : 0), degraded);
    }

    private static AgentToolProjectionUseCase.ProjectedTool tool(
            String alias, String displayName, String purpose) {
        return new AgentToolProjectionUseCase.ProjectedTool(alias, displayName, purpose,
                Map.of("type", "object", "properties",
                        Map.of("orderNo", Map.of("type", "string"))));
    }

    private static List<String> names(List<McpMetaToolCatalog.McpTool> tools) {
        return tools.stream().map(McpMetaToolCatalog.McpTool::name).toList();
    }
}
