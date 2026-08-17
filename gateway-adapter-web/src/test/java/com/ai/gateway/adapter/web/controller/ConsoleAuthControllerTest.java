package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.console.ConsoleAuthUseCase;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthenticationPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConsoleAuthControllerTest {

    @Test
    void stubLoginReturnsManagedAccessAndRefreshTokens() throws Exception {
        ConsoleAuthUseCase useCase = mock(ConsoleAuthUseCase.class);
        when(useCase.login("alice", "anything")).thenReturn(Map.of(
                "status", "OK",
                "data", Map.of("token", "access", "refreshToken", "refresh")));
        MockMvc mvc = mvc(useCase, mock(AuthenticationPort.class), "stub");

        mvc.perform(post("/admin/v1/console/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("access"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh"));

        verify(useCase).login("alice", "anything");
    }

    @Test
    void saTokenInvalidCredentialsAreRejectedAndAudited() throws Exception {
        ConsoleAuthUseCase useCase = mock(ConsoleAuthUseCase.class);
        when(useCase.login("admin", "wrong"))
                .thenThrow(new SecurityException("invalid credentials"));
        MockMvc mvc = mvc(useCase, mock(AuthenticationPort.class), "sa-token");

        mvc.perform(post("/admin/v1/console/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.errorCode").value("AUTHENTICATION_FAILED"));

        verify(useCase).login("admin", "wrong");
    }

    @Test
    void invalidRefreshTokenReturnsUnauthorizedEnvelope() throws Exception {
        ConsoleAuthUseCase useCase = mock(ConsoleAuthUseCase.class);
        when(useCase.refresh("bad-refresh"))
                .thenThrow(new SecurityException("AUTHENTICATION_FAILED"));
        MockMvc mvc = mvc(useCase, mock(AuthenticationPort.class), "stub");

        mvc.perform(post("/admin/v1/console/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"bad-refresh\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.errorCode").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void logoutRevokesAuthenticatedSession() throws Exception {
        ConsoleAuthUseCase useCase = mock(ConsoleAuthUseCase.class);
        AuthenticationPort authenticationPort = mock(AuthenticationPort.class);
        Principal principal = new Principal("alice", 1L, List.of("admin"), List.of("*"),
                Instant.now(), "TEST");
        when(authenticationPort.authenticate(any())).thenReturn(principal);
        MockMvc mvc = mvc(useCase, authenticationPort, "stub");

        mvc.perform(post("/admin/v1/console/auth/logout")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk());

        verify(useCase).logout(principal, "access-token");
    }

    private static MockMvc mvc(ConsoleAuthUseCase useCase,
                               AuthenticationPort authenticationPort,
                               String authMode) {
        ConsoleAuthController controller = new ConsoleAuthController(useCase, authenticationPort);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }
}
