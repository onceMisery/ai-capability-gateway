package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.PrincipalFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpServerSession;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthenticatedWebMvcSseServerTransportProvider} 的单元测试，覆盖会话容量拒绝、主体一致性
 * 校验、过期回收、Mcp-Session-Id 兼容回退以及优雅关闭等场景。
 *
 * @author cmiracle@163.com
 */
class AuthenticatedWebMvcSseServerTransportProviderTest {

    private static final Principal PRINCIPAL_A = principal("user-a", 1L);
    private static final Principal PRINCIPAL_B = principal("user-b", 1L);

    @Test
    void rejectsSseConnectionWhenSessionCapacityIsFull() throws Exception {
        AuthenticationPort authentication = authenticationReturning(PRINCIPAL_A);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        AuthenticatedWebMvcSseServerTransportProvider provider = provider(
                authentication, telemetry, 1, Duration.ofMinutes(5));
        addEstablishedSession(provider, "session-a", mock(McpServerSession.class),
                PRINCIPAL_A, System.nanoTime());

        ServerResponse response = invokeHandler(provider, "handleSseConnection",
                mock(ServerRequest.class));

        assertThat(response.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(provider.activeSessionCount()).isEqualTo(1);
        verify(telemetry).increment("gateway.mcp.sessions",
                Map.of("outcome", "capacity_rejected"));
    }

    @Test
    void rejectsCookieAndQueryTokenEvenWhenAuthenticationAdapterWouldAcceptThem() throws Exception {
        AuthenticationPort authentication = authenticationReturning(PRINCIPAL_A);
        AuthenticatedWebMvcSseServerTransportProvider provider = provider(
                authentication, mock(TelemetryPort.class), 1, Duration.ofMinutes(5));
        try {
            McpRequestContextHolder.set(new RequestContext(
                    Map.of(), Map.of("Authorization", "cookie-token"),
                    Map.of("Authorization", "query-token"), null));
            ServerResponse response = invokeHandler(provider, "handleSseConnection",
                    mock(ServerRequest.class));

            assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(authentication);
        } finally {
            McpRequestContextHolder.clear();
        }
    }

    @Test
    void rejectsPrincipalMismatchBeforeSessionHandle() throws Exception {
        AuthenticatedWebMvcSseServerTransportProvider provider = provider(
                authenticationReturning(PRINCIPAL_B), mock(TelemetryPort.class),
                1, Duration.ofMinutes(5));
        McpServerSession session = mock(McpServerSession.class);
        addEstablishedSession(provider, "session-a", session, PRINCIPAL_A,
                System.nanoTime());
        ServerRequest request = requestForSession("session-a");

        ServerResponse response = invokeHandler(provider, "handleMessage", request);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(session);
        assertThat(provider.activeSessionCount()).isEqualTo(1);
    }

    @Test
    void expiredSessionIsRemovedClosedAndReturnsGoneWithoutSleeping() throws Exception {
        AuthenticatedWebMvcSseServerTransportProvider provider = provider(
                authenticationReturning(PRINCIPAL_A), mock(TelemetryPort.class),
                1, Duration.ofSeconds(5));
        McpServerSession session = mock(McpServerSession.class);
        when(session.closeGracefully()).thenReturn(Mono.empty());
        addEstablishedSession(provider, "expired-session", session, PRINCIPAL_A,
                System.nanoTime() - Duration.ofMinutes(1).toNanos());

        ServerResponse response = invokeHandler(provider, "handleMessage",
                requestForSession("expired-session"));

        assertThat(response.statusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(provider.activeSessionCount()).isZero();
        verify(session).closeGracefully();
        assertThat(invokeHandler(provider, "handleSseConnection",
                mock(ServerRequest.class)).statusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void acceptsSessionIdHeaderAsCompatibilityFallback() throws Exception {
        AuthenticatedWebMvcSseServerTransportProvider provider = provider(
                authenticationReturning(PRINCIPAL_A), mock(TelemetryPort.class),
                1, Duration.ofSeconds(5));
        McpServerSession session = mock(McpServerSession.class);
        when(session.closeGracefully()).thenReturn(Mono.empty());
        addEstablishedSession(provider, "header-session", session, PRINCIPAL_A,
                System.nanoTime() - Duration.ofMinutes(1).toNanos());

        ServerRequest request = mock(ServerRequest.class);
        ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
        when(request.param("sessionId")).thenReturn(Optional.empty());
        when(request.headers()).thenReturn(headers);
        when(headers.firstHeader("Mcp-Session-Id")).thenReturn("header-session");

        ServerResponse response = invokeHandler(provider, "handleMessage", request);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.GONE);
        verify(session).closeGracefully();
    }

    @Test
    void returnsStableWrongNodeErrorForForeignSessionId() throws Exception {
        AuthenticatedWebMvcSseServerTransportProvider provider = provider(
                authenticationReturning(PRINCIPAL_A), mock(TelemetryPort.class),
                1, Duration.ofMinutes(5));
        ServerRequest request = requestForSession("other-node.session-a");

        ServerResponse response = invokeHandler(provider, "handleMessage", request);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void closeGracefullyClosesSessionsAndReleasesCapacity() throws Exception {
        AuthenticatedWebMvcSseServerTransportProvider provider = provider(
                authenticationReturning(PRINCIPAL_A), mock(TelemetryPort.class),
                1, Duration.ofMinutes(5));
        McpServerSession session = mock(McpServerSession.class);
        when(session.closeGracefully()).thenReturn(Mono.empty());
        addEstablishedSession(provider, "session-a", session, PRINCIPAL_A,
                System.nanoTime());

        provider.closeGracefully().block();

        assertThat(provider.activeSessionCount()).isZero();
        verify(session).closeGracefully();
        assertThat(availableSessionSlots(provider)).isEqualTo(1);
    }

    private static AuthenticatedWebMvcSseServerTransportProvider provider(
            AuthenticationPort authentication, TelemetryPort telemetry,
            int maxSessions, Duration idleTimeout) {
        return new AuthenticatedWebMvcSseServerTransportProvider(
                new ObjectMapper(), authentication, telemetry,
                "/mcp/message", "/mcp/sse", maxSessions, idleTimeout);
    }

    private static AuthenticationPort authenticationReturning(Principal principal) {
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        when(authentication.authenticate(any())).thenReturn(principal);
        return authentication;
    }

    private static ServerRequest requestForSession(String sessionId) {
        ServerRequest request = mock(ServerRequest.class);
        ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
        when(request.param("sessionId")).thenReturn(Optional.of(sessionId));
        when(request.headers()).thenReturn(headers);
        when(headers.firstHeader("Mcp-Session-Id")).thenReturn(null);
        return request;
    }

    private static ServerRequest.Headers headers(String authorization, String sessionId) {
        ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
        when(headers.firstHeader("Authorization")).thenReturn(authorization);
        if (sessionId != null) {
            when(headers.firstHeader("Mcp-Session-Id")).thenReturn(sessionId);
        }
        return headers;
    }

    private static Principal principal(String subject, long orgId) {
        return new Principal(subject, orgId, List.of("agent"), List.of("tools:call"),
                Instant.parse("2026-08-19T00:00:00Z"), "test");
    }

    private static ServerResponse invokeHandler(
            AuthenticatedWebMvcSseServerTransportProvider provider,
            String methodName, ServerRequest request) throws Exception {
        RequestContext existing = McpRequestContextHolder.current();
        boolean defaultContext = existing.headers().isEmpty()
                && existing.cookies().isEmpty()
                && existing.queryParams().isEmpty();
        if (defaultContext) {
            McpRequestContextHolder.set(new RequestContext(
                    Map.of("Authorization", "Bearer test"), Map.of(), Map.of(), null));
        }
        Method method = AuthenticatedWebMvcSseServerTransportProvider.class
                .getDeclaredMethod(methodName, ServerRequest.class);
        method.setAccessible(true);
        try {
            return (ServerResponse) method.invoke(provider, request);
        } finally {
            if (defaultContext) {
                McpRequestContextHolder.clear();
            }
        }
    }

    private static void addEstablishedSession(
            AuthenticatedWebMvcSseServerTransportProvider provider,
            String sessionId, McpServerSession session, Principal principal,
            long lastAccessNanos) throws Exception {
        Semaphore slots = sessionSlots(provider);
        assertThat(slots.tryAcquire()).isTrue();

        Class<?> bindingType = Class.forName(
                AuthenticatedWebMvcSseServerTransportProvider.class.getName()
                        + "$SessionBinding");
        Constructor<?> constructor = bindingType.getDeclaredConstructor(
                McpServerSession.class, String.class, long.class);
        constructor.setAccessible(true);
        Object binding = constructor.newInstance(session,
                PrincipalFingerprint.digest(principal), lastAccessNanos);

        Field sessionsField = AuthenticatedWebMvcSseServerTransportProvider.class
                .getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Object> sessions =
                (ConcurrentHashMap<String, Object>) sessionsField.get(provider);
        sessions.put(sessionId, binding);
    }

    private static int availableSessionSlots(
            AuthenticatedWebMvcSseServerTransportProvider provider) throws Exception {
        return sessionSlots(provider).availablePermits();
    }

    private static Semaphore sessionSlots(
            AuthenticatedWebMvcSseServerTransportProvider provider) throws Exception {
        Field slotsField = AuthenticatedWebMvcSseServerTransportProvider.class
                .getDeclaredField("sessionSlots");
        slotsField.setAccessible(true);
        return (Semaphore) slotsField.get(provider);
    }
}
