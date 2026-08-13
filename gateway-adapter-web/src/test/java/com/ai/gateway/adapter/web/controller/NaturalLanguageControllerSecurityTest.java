package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.application.runtime.ClarificationUseCase;
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthenticationPort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NaturalLanguageControllerSecurityTest {

    @Test
    void clarificationContinuationBindsSessionToAuthenticatedSubject() throws Exception {
        NaturalLanguageQueryUseCase queryUseCase = mock(NaturalLanguageQueryUseCase.class);
        ClarificationUseCase clarificationUseCase = mock(ClarificationUseCase.class);
        AuthenticationPort authenticationPort = mock(AuthenticationPort.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer secret-token-value");
        Principal principal = new Principal("subject-42", 1L, List.of("user"), List.of(),
                Instant.now(), "TEST");
        when(authenticationPort.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(principal);
        when(clarificationUseCase.continueClarification(eq("interaction-1"), eq("more"),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ClarificationUseCase.ClarificationResult(
                        ClarificationUseCase.ClarificationStatus.INVALID,
                        null, null, null, "done"));
        NaturalLanguageController controller = new NaturalLanguageController(queryUseCase,
                clarificationUseCase, new RequestContextFactory(), authenticationPort);

        controller.continueClarification("interaction-1",
                new NaturalLanguageController.ClarificationMessageRequest("more"),
                "Bearer secret-token-value", request);

        String expectedDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("subject-42".getBytes(StandardCharsets.UTF_8)));
        verify(clarificationUseCase).continueClarification(
                "interaction-1", "more", expectedDigest);
    }
}
