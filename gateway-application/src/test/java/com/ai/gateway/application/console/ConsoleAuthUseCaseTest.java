package com.ai.gateway.application.console;

import com.ai.gateway.domain.model.AuditEvent;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuditPort;
import com.ai.gateway.domain.port.TokenIssuerPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsoleAuthUseCaseTest {

    @Test
    void loginIssuesManagedTokenPairAndRecordsAudit() {
        TokenIssuerPort tokenIssuer = mock(TokenIssuerPort.class);
        AuditPort auditPort = mock(AuditPort.class);
        when(tokenIssuer.issueTokenPair("alice", Map.of(
                "orgId", 0L,
                "roles", List.of("admin"),
                "permissions", List.of("*"))))
                .thenReturn(new TokenIssuerPort.TokenPair("access", "refresh", 60L, 120L));
        ConsoleAuthUseCase useCase = new ConsoleAuthUseCase(tokenIssuer, auditPort, "stub");

        Map<String, Object> response = useCase.login("alice");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> principal = (Map<String, Object>) data.get("principal");
        assertThat(data).containsEntry("token", "access")
                .containsEntry("refreshToken", "refresh")
                .containsEntry("expiresInSeconds", 60L)
                .containsEntry("refreshExpiresInSeconds", 120L);
        assertThat(principal).containsEntry("authMethod", "STUB_JWT");
        verify(auditPort).recordEvent(argThat(event ->
                "CONSOLE_LOGIN".equals(event.eventType())
                        && "SUCCESS".equals(event.resultCode())
                        && !"alice".equals(event.subjectDigest())
                        && event.subjectDigest().matches("[0-9a-f]{64}")));
    }

    @Test
    void refreshRotatesThroughTokenIssuer() {
        TokenIssuerPort tokenIssuer = mock(TokenIssuerPort.class);
        AuditPort auditPort = mock(AuditPort.class);
        when(tokenIssuer.refresh("old-refresh"))
                .thenReturn(new TokenIssuerPort.TokenPair("new-access", "new-refresh", 60L, 120L));
        ConsoleAuthUseCase useCase = new ConsoleAuthUseCase(tokenIssuer, auditPort, "sa-token");

        Map<String, Object> response = useCase.refresh("old-refresh");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data).containsEntry("token", "new-access")
                .containsEntry("refreshToken", "new-refresh");
    }

    @Test
    void logoutRevokesSessionBeforeRecordingAudit() {
        TokenIssuerPort tokenIssuer = mock(TokenIssuerPort.class);
        AuditPort auditPort = mock(AuditPort.class);
        ConsoleAuthUseCase useCase = new ConsoleAuthUseCase(tokenIssuer, auditPort, "sa-token");
        Principal principal = new Principal("alice", 7L, List.of("admin"), List.of("*"),
                Instant.now(), "SA_TOKEN_JWT");

        useCase.logout(principal, "access-token");

        verify(tokenIssuer).revokeToken("access-token");
        verify(auditPort).recordEvent(argThat(event ->
                "CONSOLE_LOGOUT".equals(event.eventType())
                        && !"alice".equals(event.subjectDigest())
                        && event.subjectDigest().matches("[0-9a-f]{64}")));
    }

    @Test
    void failedLoginIsAuditedWithoutRawUsername() {
        TokenIssuerPort tokenIssuer = mock(TokenIssuerPort.class);
        AuditPort auditPort = mock(AuditPort.class);
        ConsoleAuthUseCase useCase = new ConsoleAuthUseCase(tokenIssuer, auditPort, "sa-token");

        useCase.recordLoginFailure("alice");

        verify(auditPort).recordEvent(argThat(event ->
                "CONSOLE_LOGIN".equals(event.eventType())
                        && "AUTHENTICATION_FAILED".equals(event.resultCode())
                        && !"alice".equals(event.subjectDigest())
                        && event.subjectDigest().matches("[0-9a-f]{64}")));
    }
}
