package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.handler.GlobalExceptionHandler;
import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.application.runtime.AgentToolCallUseCase;
import com.ai.gateway.application.runtime.AgentToolCatalogUseCase;
import com.ai.gateway.application.runtime.StructuredInvocationUseCase;
import com.ai.gateway.domain.model.ConfirmationToken;
import com.ai.gateway.domain.model.RequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ToolControllerAgentIntegrationTest {

    @Test
    void resolveSeparatesModelToolsFromHostBindings() throws Exception {
        AgentToolCatalogUseCase catalog = mock(AgentToolCatalogUseCase.class);
        when(catalog.resolve(any(), eq("query order"), eq(5))).thenReturn(
                new AgentToolCatalogUseCase.Resolution(8L,
                        List.of(new AgentToolCatalogUseCase.Candidate(
                                "cap_ABC", "Query order", "Query an order",
                                Map.of("type", "object"), "DIRECT", 3.2)),
                        List.of(new AgentToolCatalogUseCase.Binding(
                                "cap_ABC", "orders.query", "1.0.0", 8L))));
        MockMvc mvc = mvc(catalog, mock(AgentToolCallUseCase.class));

        mvc.perform(post("/api/v1/tools:resolve")
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-1\",\"query\":\"query order\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.snapshotVersion").value(8))
                .andExpect(jsonPath("$.tools[0].toolName").value("cap_ABC"))
                .andExpect(jsonPath("$.tools[0].capabilityId").doesNotExist())
                .andExpect(jsonPath("$.bindings[0].capabilityId").value("orders.query"));
    }

    @Test
    void callReturnsCompletedDataForRead() throws Exception {
        AgentToolCallUseCase call = mock(AgentToolCallUseCase.class);
        when(call.call(any(), eq("req-1"), eq("orders.query"), eq("1.0.0"),
                any(), eq("zh-CN"), eq(8L), eq("req-1")))
                .thenReturn(new AgentToolCallUseCase.Result(
                        AgentToolCallUseCase.Status.COMPLETED, Map.of("ok", true), null,
                        "done", 8L, "orders.query", "1.0.0", null, null, null));
        MockMvc mvc = mvc(mock(AgentToolCatalogUseCase.class), call);

        mvc.perform(post("/api/v1/tools/orders.query:call")
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-1\",\"version\":\"1.0.0\","
                                + "\"arguments\":{},\"locale\":\"zh-CN\","
                                + "\"snapshotVersion\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.ok").value(true));
    }

    @Test
    void callReturnsHostConfirmationTokenForLowRiskWrite() throws Exception {
        AgentToolCallUseCase call = mock(AgentToolCallUseCase.class);
        ConfirmationToken token = new ConfirmationToken("secret-token", "op-1", "principal",
                7L, "args", "signature", Instant.parse("2026-08-19T15:00:00Z"), false);
        when(call.call(any(), eq("req-2"), eq("orders.update"), eq("1.0.0"),
                any(), eq("zh-CN"), eq(8L), eq("idempotency-1")))
                .thenReturn(new AgentToolCallUseCase.Result(
                        AgentToolCallUseCase.Status.CONFIRMATION_REQUIRED, null, null,
                        "Confirm order update", 8L, "orders.update", "1.0.0", "op-1",
                        token, token.expiresAt()));
        MockMvc mvc = mvc(mock(AgentToolCatalogUseCase.class), call);

        mvc.perform(post("/api/v1/tools/orders.update:call")
                        .header("Idempotency-Key", "idempotency-1")
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-2\",\"version\":\"1.0.0\","
                                + "\"arguments\":{},\"locale\":\"zh-CN\","
                                + "\"snapshotVersion\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.operationId").value("op-1"))
                .andExpect(jsonPath("$.confirmationToken").value("secret-token"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void invalidCallBodyIsRejectedBeforeUseCase() throws Exception {
        AgentToolCallUseCase call = mock(AgentToolCallUseCase.class);
        MockMvc mvc = mvc(mock(AgentToolCatalogUseCase.class), call);

        mvc.perform(post("/api/v1/tools/orders.query:call")
                        .contentType("application/json")
                        .content("{\"requestId\":\"\",\"version\":\"1.0.0\","
                                + "\"arguments\":{},\"locale\":\"zh-CN\","
                                + "\"snapshotVersion\":8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.errorCode")
                        .value("ARGUMENT_VALIDATION_FAILED"));
    }

    private static MockMvc mvc(AgentToolCatalogUseCase catalog,
                               AgentToolCallUseCase call) {
        RequestContextFactory contextFactory = mock(RequestContextFactory.class);
        when(contextFactory.from(any())).thenReturn(RequestContext.empty());
        ToolController controller = new ToolController(
                mock(StructuredInvocationUseCase.class), contextFactory, catalog, call);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
