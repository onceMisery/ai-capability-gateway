package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.handler.GlobalExceptionHandler;
import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.application.agent.AgentCapabilityResolver;
import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper;
import com.ai.gateway.domain.model.RequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentToolControllerTest {

    @Test
    void resolveReturnsOpaqueCandidatesWithoutCapabilityIdentity() throws Exception {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.resolve(any(), org.mockito.ArgumentMatchers.eq("req-1"),
                org.mockito.ArgumentMatchers.eq("req-1"),
                org.mockito.ArgumentMatchers.eq("query order"),
                org.mockito.ArgumentMatchers.eq(5))).thenReturn(
                new AgentHostConnector.ResolveResult(new AgentCapabilityResolver.Resolution(
                        AgentCapabilityResolver.Status.RESOLVED, null, 8L, 42L,
                        List.of(new AgentCapabilityResolver.Candidate(
                                "ref-opaque", "Query order", "Find an order",
                                com.ai.gateway.application.agent.CapabilityPublicProjectionService.SchemaClass.SIMPLE,
                                Map.of("required", List.of("orderNo")), "DIRECT")),
                        null, Instant.now().plusSeconds(60)), null));

        mvc(connector)
                .perform(post("/api/v1/agent/tools:resolve")
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-1\",\"query\":\"query order\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].toolRef").value("ref-opaque"))
                .andExpect(jsonPath("$.candidates[0].capabilityId").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].binding").doesNotExist());
    }

    @Test
    void resolveCapacityRejectionReturnsTooManyRequests() throws Exception {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.resolve(any(), org.mockito.ArgumentMatchers.eq("req-1"),
                org.mockito.ArgumentMatchers.eq("req-1"),
                org.mockito.ArgumentMatchers.eq("query order"),
                org.mockito.ArgumentMatchers.eq(5))).thenReturn(
                new AgentHostConnector.ResolveResult(new AgentCapabilityResolver.Resolution(
                        AgentCapabilityResolver.Status.ERROR,
                        "RESOLVE_CAPACITY_EXCEEDED", 0L, 0L,
                        List.of(), null, null), null));

        mvc(connector)
                .perform(post("/api/v1/agent/tools:resolve")
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-1\",\"query\":\"query order\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RESOLVE_CAPACITY_EXCEEDED"));
    }

    @Test
    void schemaReturnsOnlyTheRequestedToolReferenceSchema() throws Exception {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.schema(any(), org.mockito.ArgumentMatchers.eq("req-1"),
                org.mockito.ArgumentMatchers.eq("ref-opaque")))
                .thenReturn(new AgentHostConnector.SchemaResult(new AgentCapabilityResolver.SchemaResult(
                        AgentCapabilityResolver.Status.RESOLVED, null, "ref-opaque",
                        com.ai.gateway.application.agent.CapabilityPublicProjectionService.SchemaClass.STANDARD,
                        Map.of("type", "object"), Instant.now().plusSeconds(60)), null));

        mvc(connector)
                .perform(post("/api/v1/agent/tools:schema")
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-1\",\"toolRef\":\"ref-opaque\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolRef").value("ref-opaque"))
                .andExpect(jsonPath("$.inputSchema.type").value("object"));
    }

    @Test
    void callMapsConflictAndExpiredReferenceToStableHttpStatuses() throws Exception {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.call(any(), org.mockito.ArgumentMatchers.eq("req-1"),
                org.mockito.ArgumentMatchers.eq("req-1"),
                org.mockito.ArgumentMatchers.eq("ref-opaque"), any(),
                org.mockito.ArgumentMatchers.eq("zh-CN"), any()))
                .thenReturn(new AgentHostConnector.CallResult(
                        new AgentModelResultMapper.ModelResult(
                                AgentModelResultMapper.ModelResult.Status.ERROR, null,
                                "POLICY_CHANGED", "policy changed", null, null),
                        null, null));

        mvc(connector)
                .perform(post("/api/v1/agent/tools:call")
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-1\",\"toolRef\":\"ref-opaque\","
                                + "\"arguments\":{},\"locale\":\"zh-CN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("POLICY_CHANGED"));
    }

    @Test
    void trustedHostCanConfirmWithoutReceivingTokenFromTheModel() throws Exception {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.confirm(any())).thenReturn(
                new AgentHostConnector.ConfirmationResult(
                        true, "SUCCEEDED", "completed"));

        mvc(connector)
                .perform(post("/api/v1/agent/operations/op-1:confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.operationId").value("op-1"))
                .andExpect(jsonPath("$.confirmationToken").doesNotExist());
    }

    @Test
    void unavailableHostConfirmationReturnsConflictWithoutCallingModelTool() throws Exception {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.confirm(any())).thenReturn(
                new AgentHostConnector.ConfirmationResult(
                        false, "CONFIRMATION_NOT_AVAILABLE", null));

        mvc(connector)
                .perform(post("/api/v1/agent/operations/op-1:confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CONFIRMATION_NOT_AVAILABLE"));
    }

    @Test
    void trustedHostCanQueryCanonicalOperationStatus() throws Exception {
        AgentHostConnector connector = mock(AgentHostConnector.class);
        when(connector.status(any(), org.mockito.ArgumentMatchers.eq("op-1")))
                .thenReturn(new AgentHostConnector.OperationStatusResult(
                        true, "UNKNOWN", Instant.now().plusSeconds(60), null));

        mvc(connector)
                .perform(get("/api/v1/agent/operations/op-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.operationId").value("op-1"))
                .andExpect(jsonPath("$.confirmationToken").doesNotExist());
    }

    private static MockMvc mvc(AgentHostConnector connector) {
        RequestContextFactory contextFactory = mock(RequestContextFactory.class);
        when(contextFactory.from(any())).thenReturn(RequestContext.empty());
        AgentToolController controller = new AgentToolController(connector, contextFactory);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
