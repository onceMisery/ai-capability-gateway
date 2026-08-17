package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.application.operation.OperationConfirmUseCase;
import com.ai.gateway.application.operation.OperationCancelUseCase;
import com.ai.gateway.application.operation.OperationPrepareUseCase;
import com.ai.gateway.application.operation.OperationStatusUseCase;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OperationState;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperationControllerSecurityTest {

    private MockMvc mockMvc;
    private AuthenticationPort authentication;

    @BeforeEach
    void setUp() {
        OperationPrepareUseCase prepare = mock(OperationPrepareUseCase.class);
        OperationConfirmUseCase confirm = mock(OperationConfirmUseCase.class);
        OperationCancelUseCase cancel = mock(OperationCancelUseCase.class);
        OperationStatusUseCase status = mock(OperationStatusUseCase.class);
        authentication = mock(AuthenticationPort.class);
        RequestContextFactory contextFactory = mock(RequestContextFactory.class);

        OperationRecord record = new OperationRecord(
                "op-1", OperationState.PREPARED, "owner-digest", 7L,
                "order.create", "1.0.0", "manifest", 1L,
                "cipher", "arguments", "idem", "policy", null,
                Instant.now().plusSeconds(300), 0L);
        when(status.query("op-1")).thenReturn(record);
        when(contextFactory.from(any())).thenReturn(mock(RequestContext.class));
        when(authentication.authenticate(any())).thenThrow(new IllegalArgumentException("missing token"));

        OperationController controller = new OperationController(
                prepare, confirm, cancel, status, authentication, contextFactory);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void statusRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/operations/op-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancelRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/operations/op-1:cancel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statusHidesOperationFromDifferentPrincipal() throws Exception {
        doReturn(new Principal("attacker", 7L, List.of(), List.of(), Instant.now(), "JWT"))
                .when(authentication).authenticate(any());

        mockMvc.perform(get("/api/v1/operations/op-1")
                        .header("Authorization", "Bearer attacker"))
                .andExpect(status().isNotFound());
    }
}
