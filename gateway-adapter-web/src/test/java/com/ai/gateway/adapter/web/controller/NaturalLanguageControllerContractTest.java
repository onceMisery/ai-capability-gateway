package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.handler.GlobalExceptionHandler;
import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.application.runtime.ClarificationUseCase;
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NaturalLanguageControllerContractTest {

    @Test
    void blankQueryTextIsRejectedByBeanValidation() throws Exception {
        MockMvc mvc = mvc(mock(NaturalLanguageQueryUseCase.class));

        mvc.perform(post("/api/v1/natural-language/queries")
                        .header("Authorization", "Bearer token")
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-1\",\"text\":\" \",\"locale\":\"zh-CN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.error.errorCode")
                        .value("ARGUMENT_VALIDATION_FAILED"));
    }

    @Test
    void completedResponsePreservesRequestIdAndAddsGovernedExecutionMetadata()
            throws Exception {
        NaturalLanguageQueryUseCase useCase = mock(NaturalLanguageQueryUseCase.class);
        var result = new NaturalLanguageQueryUseCase.QueryResult(
                NaturalLanguageQueryUseCase.QueryStatus.COMPLETED,
                Map.of("value", 7), "done", null, 3L, null, null,
                Map.of("id", "order.query", "version", "1.0.0"),
                Map.of("id", "exec-1", "status", "COMPLETED"), null);
        when(useCase.execute(any(), eq("req-1"), eq("query"), eq("zh-CN"), eq("UTC")))
                .thenReturn(result);
        MockMvc mvc = mvc(useCase);

        mvc.perform(post("/api/v1/natural-language/queries")
                        .header("Authorization", "Bearer token")
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-1\",\"text\":\"query\",\"locale\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.capability.id").value("order.query"))
                .andExpect(jsonPath("$.execution.id").value("exec-1"));
    }

    @Test
    void clarificationResponseIncludesExpiry() throws Exception {
        NaturalLanguageQueryUseCase useCase = mock(NaturalLanguageQueryUseCase.class);
        Instant expiresAt = Instant.parse("2026-08-10T11:00:00Z");
        var result = new NaturalLanguageQueryUseCase.QueryResult(
                NaturalLanguageQueryUseCase.QueryStatus.CLARIFICATION_REQUIRED,
                null, "which order?", "interaction-1", 3L,
                "CLARIFICATION_REQUIRED", null, null, null, expiresAt);
        when(useCase.execute(any(), eq("req-2"), eq("query"), eq("zh-CN"), eq("UTC")))
                .thenReturn(result);
        MockMvc mvc = mvc(useCase);

        mvc.perform(post("/api/v1/natural-language/queries")
                        .header("Authorization", "Bearer token")
                        .contentType("application/json")
                        .content("{\"requestId\":\"req-2\",\"text\":\"query\",\"locale\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-2"))
                .andExpect(jsonPath("$.expiresAt").value("2026-08-10T11:00:00Z"));
    }

    private static MockMvc mvc(NaturalLanguageQueryUseCase queryUseCase) {
        RequestContextFactory contextFactory = mock(RequestContextFactory.class);
        when(contextFactory.from(any())).thenReturn(RequestContext.empty());
        NaturalLanguageController controller = new NaturalLanguageController(
                queryUseCase, mock(ClarificationUseCase.class), contextFactory,
                mock(AuthenticationPort.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
