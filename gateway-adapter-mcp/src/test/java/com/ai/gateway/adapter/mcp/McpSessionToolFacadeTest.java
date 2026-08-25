package com.ai.gateway.adapter.mcp;

import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper;
import com.ai.gateway.application.agent.AgentToolProjectionUseCase;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.TelemetryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Acceptance tests for the JSON-RPC interception seam (design §4.2, §4.4).
 *
 * <p>The MCP SDK registers tools statically and server-globally, which cannot express a
 * per-identity tool face. This facade closes that gap by answering {@code tools/list} and
 * alias-shaped {@code tools/call} at the JSON-RPC layer. The tests pin the boundary of
 * that interception: it never runs without an authenticated context, never shadows the two
 * meta tools (one path per behaviour), always goes through {@link McpCallExecutor} so the
 * bulkhead and deadline still apply, and translates every failure into a stable code
 * without echoing internal exception text.</p>
 *
 * @author cmiracle@163.com
 */
class McpSessionToolFacadeTest {

    private static final AgentToolProjectionUseCase.ProjectionBudget BUDGET =
            new AgentToolProjectionUseCase.ProjectionBudget(64, 131_072L);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void metaToolOnlyDeploymentInterceptsNothingAndLeavesTheSdkRegistryInCharge() {
        McpSessionToolFacade facade = new McpSessionToolFacade(
                adapter(mock(AgentHostConnector.class), McpProjectedToolCatalog.metaToolOnly()),
                MAPPER, McpCallExecutor.direct());

        assertThat(facade.intercept(McpSchema.METHOD_TOOLS_LIST, Map.of(),
                RequestContext.empty(), deadline())).isNull();
        assertThat(facade.intercept(McpSchema.METHOD_TOOLS_CALL,
                Map.of("name", "cap_aaa", "arguments", Map.of()),
                RequestContext.empty(), deadline())).isNull();
    }

    @Test
    void anUnauthenticatedRequestIsNeverInterceptedSoNoToolFaceLeaksWithoutIdentity() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        McpSessionToolFacade facade = facade(mock(AgentHostConnector.class), useCase,
                McpCallExecutor.direct());

        assertThat(facade.intercept(McpSchema.METHOD_TOOLS_LIST, Map.of(), null,
                deadline())).isNull();

        verifyNoInteractions(useCase);
    }

    @Test
    void methodsOtherThanTheTwoToolMethodsAreLeftToTheSdk() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        McpSessionToolFacade facade = facade(mock(AgentHostConnector.class), useCase,
                McpCallExecutor.direct());

        assertThat(facade.intercept("resources/list", Map.of(), RequestContext.empty(),
                deadline())).isNull();

        verifyNoInteractions(useCase);
    }

    @Test
    void toolsListIsAnsweredWithTheIdentityScopedProjectionRenderedAsSdkTools() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.project(any(), eq(BUDGET))).thenReturn(projection(
                new AgentToolProjectionUseCase.ProjectedTool("cap_aaa", "Order detail",
                        "Query one order", Map.of("type", "object", "properties",
                        Map.of("orderNo", Map.of("type", "string"))))));
        McpSessionToolFacade facade = facade(mock(AgentHostConnector.class), useCase,
                McpCallExecutor.direct());

        Object intercepted = facade.intercept(McpSchema.METHOD_TOOLS_LIST, Map.of(),
                RequestContext.empty(), deadline());

        assertThat(intercepted).isInstanceOf(McpSchema.ListToolsResult.class);
        McpSchema.ListToolsResult result = (McpSchema.ListToolsResult) intercepted;
        assertThat(result.tools()).extracting(McpSchema.Tool::name)
                .containsExactly("cap_aaa", "gateway_resolve", "gateway_call");
        assertThat(result.tools().get(0).description()).isEqualTo("Query one order");
        assertThat(result.tools().get(0).inputSchema().properties()).containsKey("orderNo");
    }

    @Test
    void oneUnserializableSchemaIsSkippedInsteadOfBlankingTheWholeToolFace() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.project(any(), eq(BUDGET))).thenReturn(projection(
                new AgentToolProjectionUseCase.ProjectedTool("cap_bad", "Broken",
                        "Schema cannot be serialized", Map.of("properties", new Object())),
                new AgentToolProjectionUseCase.ProjectedTool("cap_aaa", "Order detail",
                        "Query one order", Map.of("type", "object"))));
        McpSessionToolFacade facade = facade(mock(AgentHostConnector.class), useCase,
                McpCallExecutor.direct());

        McpSchema.ListToolsResult result = (McpSchema.ListToolsResult) facade.intercept(
                McpSchema.METHOD_TOOLS_LIST, Map.of(), RequestContext.empty(), deadline());

        assertThat(result.tools()).extracting(McpSchema.Tool::name)
                .containsExactly("cap_aaa", "gateway_resolve", "gateway_call");
    }

    @Test
    void metaToolCallsAreHandedBackToTheSdkSoThereIsExactlyOneMetaToolPath() {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        McpSessionToolFacade facade = facade(connector,
                mock(AgentToolProjectionUseCase.class), McpCallExecutor.direct());

        assertThat(facade.intercept(McpSchema.METHOD_TOOLS_CALL,
                Map.of("name", "gateway_resolve", "arguments", Map.of("query", "订单")),
                RequestContext.empty(), deadline())).isNull();
        assertThat(facade.intercept(McpSchema.METHOD_TOOLS_CALL,
                Map.of("name", "gateway_call", "arguments", Map.of()),
                RequestContext.empty(), deadline())).isNull();

        verifyNoInteractions(connector);
    }

    @Test
    void unparseableCallParamsAreHandedBackToTheSdkForItsStandardProtocolError() {
        McpSessionToolFacade facade = facade(mock(AgentHostConnector.class),
                mock(AgentToolProjectionUseCase.class), McpCallExecutor.direct());

        assertThat(facade.intercept(McpSchema.METHOD_TOOLS_CALL, "not-an-object",
                RequestContext.empty(), deadline())).isNull();
        assertThat(facade.intercept(McpSchema.METHOD_TOOLS_CALL, null,
                RequestContext.empty(), deadline())).isNull();
    }

    @Test
    void aliasCallStillCrossesTheExecutorSoTheBulkheadAndDeadlineKeepApplying() {
        RecordingExecutor executor = new RecordingExecutor();
        McpSessionToolFacade facade = facade(completingConnector(),
                bindingUseCase(), executor);
        long deadlineNanos = deadline();

        McpSchema.CallToolResult result = (McpSchema.CallToolResult) facade.intercept(
                McpSchema.METHOD_TOOLS_CALL,
                Map.of("name", "cap_aaa", "arguments", Map.of("orderNo", "A-1")),
                RequestContext.empty(), deadlineNanos);

        assertThat(executor.invocations).isEqualTo(1);
        assertThat(executor.deadlineNanos.get()).isEqualTo(deadlineNanos);
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"status\":\"COMPLETED\"");
    }

    @Test
    void requestIdIsDerivedDeterministicallySoARetryStaysTheSameOperation() {
        McpSessionToolFacade facade = facade(completingConnector(), bindingUseCase(),
                McpCallExecutor.direct());
        Map<String, Object> params = Map.of("name", "cap_aaa",
                "arguments", Map.of("orderNo", "A-1"));

        String first = text((McpSchema.CallToolResult) facade.intercept(
                McpSchema.METHOD_TOOLS_CALL, params, RequestContext.empty(), deadline()));
        String repeated = text((McpSchema.CallToolResult) facade.intercept(
                McpSchema.METHOD_TOOLS_CALL, params, RequestContext.empty(), deadline()));
        String otherArguments = text((McpSchema.CallToolResult) facade.intercept(
                McpSchema.METHOD_TOOLS_CALL,
                Map.of("name", "cap_aaa", "arguments", Map.of("orderNo", "A-2")),
                RequestContext.empty(), deadline()));

        assertThat(requestId(first)).startsWith("mcp-").isEqualTo(requestId(repeated));
        assertThat(requestId(otherArguments)).isNotEqualTo(requestId(first));
    }

    @Test
    void gatewayLevelRejectionIsReportedAsAnErrorResultWithTheStableCode() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.bind(any(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentToolProjectionUseCase.BindResult(
                        AgentToolProjectionUseCase.Status.ERROR, "CAPABILITY_UNAVAILABLE",
                        null, null, null, null, 0L, 0L));
        AgentHostConnector connector = mock(AgentHostConnector.class);
        McpSessionToolFacade facade = facade(connector, useCase, McpCallExecutor.direct());

        McpSchema.CallToolResult result = (McpSchema.CallToolResult) facade.intercept(
                McpSchema.METHOD_TOOLS_CALL,
                Map.of("name", "cap_aaa", "arguments", Map.of()),
                RequestContext.empty(), deadline());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("CAPABILITY_UNAVAILABLE");
        verifyNoInteractions(connector);
    }

    @Test
    void deadlineExhaustionIsMappedToAStableTimeoutCode() {
        assertThat(text(failWith(new TimeoutException("waited 30s"))))
                .contains("MCP_CALL_TIMEOUT");
    }

    @Test
    void executorSaturationIsMappedToAStableCapacityCode() {
        assertThat(text(failWith(new RejectedExecutionException("queue full"))))
                .contains("MCP_CALL_CAPACITY_EXCEEDED");
    }

    @Test
    void anyOtherFailureIsGenericAndNeverEchoesTheInternalMessage() {
        McpSchema.CallToolResult result = failWith(
                new IllegalStateException("jdbc connection to orders-db refused"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("MCP_CALL_FAILED")
                .doesNotContain("orders-db")
                .doesNotContain("IllegalStateException");
    }

    /** Runs an alias call whose execution fails with the given cause. */
    private static McpSchema.CallToolResult failWith(Exception cause) {
        McpCallExecutor executor = new McpCallExecutor() {
            @Override
            public <T> Mono<T> execute(Callable<T> task, long deadlineNanos) {
                return Mono.error(cause);
            }
        };
        McpSessionToolFacade facade = facade(mock(AgentHostConnector.class),
                mock(AgentToolProjectionUseCase.class), executor);
        return (McpSchema.CallToolResult) facade.intercept(McpSchema.METHOD_TOOLS_CALL,
                Map.of("name", "cap_aaa", "arguments", Map.of()),
                RequestContext.empty(), deadline());
    }

    private static McpSessionToolFacade facade(AgentHostConnector connector,
                                               AgentToolProjectionUseCase useCase,
                                               McpCallExecutor executor) {
        return new McpSessionToolFacade(adapter(connector,
                McpProjectedToolCatalog.of(McpToolExposureMode.HYBRID, useCase, BUDGET,
                        mock(TelemetryPort.class))), MAPPER, executor);
    }

    private static McpGatewayAdapter adapter(AgentHostConnector connector,
                                             McpProjectedToolCatalog catalog) {
        return new McpGatewayAdapter(connector, McpSecurityMode.READ_ONLY,
                McpClientTrustRegistry.disabled(), McpRateLimiter.allowAll(), catalog);
    }

    private static AgentToolProjectionUseCase bindingUseCase() {
        AgentToolProjectionUseCase useCase = mock(AgentToolProjectionUseCase.class);
        when(useCase.bind(any(), eq("cap_aaa"), anyString(), anyString()))
                .thenAnswer(invocation -> new AgentToolProjectionUseCase.BindResult(
                        AgentToolProjectionUseCase.Status.COMPLETED, null, "cap_aaa",
                        "tool-ref", invocation.getArgument(2),
                        Instant.now().plusSeconds(120L), 9L, 5L));
        return useCase;
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

    private static AgentToolProjectionUseCase.ProjectionResult projection(
            AgentToolProjectionUseCase.ProjectedTool... tools) {
        return new AgentToolProjectionUseCase.ProjectionResult(
                AgentToolProjectionUseCase.Status.COMPLETED, null, 9L, 5L,
                java.util.List.of(tools), tools.length, false);
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static String requestId(String payload) {
        try {
            return MAPPER.readTree(payload).path("requestId").asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("payload is not valid JSON: " + payload, e);
        }
    }

    private static long deadline() {
        return System.nanoTime() + java.time.Duration.ofSeconds(30L).toNanos();
    }

    /** Captures the deadline the facade hands to the executor. */
    private static final class RecordingExecutor implements McpCallExecutor {

        private final AtomicLong deadlineNanos = new AtomicLong();
        private int invocations;

        @Override
        public <T> Mono<T> execute(Callable<T> task, long deadlineNanos) {
            this.deadlineNanos.set(deadlineNanos);
            this.invocations++;
            return Mono.fromCallable(task);
        }
    }
}
