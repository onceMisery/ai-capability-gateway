package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.PrincipalFingerprint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/** MCP 0.10 WebMVC SSE transport with authenticated, principal-bound sessions. */
public final class AuthenticatedWebMvcSseServerTransportProvider
        implements McpServerTransportProvider {

    private static final String MESSAGE_EVENT_TYPE = "message";
    private static final String ENDPOINT_EVENT_TYPE = "endpoint";

    private final ObjectMapper objectMapper;
    private final AuthenticationPort authenticationPort;
    private final TelemetryPort telemetry;
    private final String messageEndpoint;
    private final String sseEndpoint;
    private final Duration idleTimeout;
    private final RouterFunction<ServerResponse> routerFunction;
    private final ConcurrentHashMap<String, SessionBinding> sessions =
            new ConcurrentHashMap<>();
    private final Semaphore sessionSlots;
    private volatile McpServerSession.Factory sessionFactory;
    private volatile boolean closing;

    public AuthenticatedWebMvcSseServerTransportProvider(
            ObjectMapper objectMapper,
            AuthenticationPort authenticationPort,
            TelemetryPort telemetry,
            String messageEndpoint,
            String sseEndpoint,
            int maxSessions,
            Duration idleTimeout) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.messageEndpoint = requirePath(messageEndpoint, "messageEndpoint");
        this.sseEndpoint = requirePath(sseEndpoint, "sseEndpoint");
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive");
        }
        this.idleTimeout = Objects.requireNonNull(idleTimeout);
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
        this.sessionSlots = new Semaphore(maxSessions);
        this.routerFunction = RouterFunctions.route()
                .GET(this.sseEndpoint, this::handleSseConnection)
                .POST(this.messageEndpoint, this::handleMessage)
                .build();
        telemetry.recordValue("gateway.mcp.sessions.capacity", maxSessions,
                Map.of("resource", "sse"));
        recordSessionCount();
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory);
    }

    public RouterFunction<ServerResponse> getRouterFunction() {
        return routerFunction;
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return Flux.fromIterable(sessions.values())
                .flatMap(binding -> binding.session().sendNotification(method, params)
                        .onErrorComplete())
                .then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        closing = true;
        List<McpServerSession> closingSessions = new ArrayList<>();
        for (String sessionId : List.copyOf(sessions.keySet())) {
            SessionBinding binding = removeSession(sessionId);
            if (binding != null) {
                closingSessions.add(binding.session());
            }
        }
        return Flux.fromIterable(closingSessions)
                .flatMap(McpServerSession::closeGracefully)
                .then();
    }

    private ServerResponse handleSseConnection(ServerRequest request) {
        if (closing) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        Principal principal = authenticate(McpRequestContextHolder.current());
        if (principal == null) {
            return unauthorized();
        }
        evictExpiredSessions();
        if (!sessionSlots.tryAcquire()) {
            telemetry.increment("gateway.mcp.sessions",
                    Map.of("outcome", "capacity_rejected"));
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        String sessionId = UUID.randomUUID().toString();
        String principalFingerprint = PrincipalFingerprint.digest(principal);
        try {
            return ServerResponse.sse(sse -> {
                try {
                    SessionTransport transport = new SessionTransport(sessionId, sse);
                    McpServerSession session = Objects.requireNonNull(sessionFactory,
                            "MCP session factory is not initialized").create(transport);
                    SessionBinding binding = new SessionBinding(
                            session, principalFingerprint, System.currentTimeMillis());
                    sessions.put(sessionId, binding);
                    recordSessionCount();
                    telemetry.increment("gateway.mcp.sessions",
                            Map.of("outcome", "opened"));
                    sse.onComplete(() -> removeSession(sessionId));
                    sse.onTimeout(() -> removeSession(sessionId));
                    sse.id(sessionId).event(ENDPOINT_EVENT_TYPE)
                            .data(messageEndpoint + "?sessionId=" + sessionId);
                } catch (IOException | RuntimeException e) {
                    removeOrReleaseUnpublished(sessionId);
                    sse.error(e);
                }
            }, Duration.ZERO);
        } catch (RuntimeException e) {
            removeOrReleaseUnpublished(sessionId);
            return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private ServerResponse handleMessage(ServerRequest request) {
        if (closing) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        String sessionId = request.param("sessionId").orElse(null);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = request.headers().firstHeader("Mcp-Session-Id");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return ServerResponse.badRequest().body(new McpError("Session ID missing"));
        }
        SessionBinding binding = sessions.get(sessionId);
        if (binding == null) {
            return ServerResponse.status(HttpStatus.NOT_FOUND)
                    .body(new McpError("Session not found"));
        }
        if (binding.isExpired(idleTimeout)) {
            removeAndClose(sessionId);
            telemetry.increment("gateway.mcp.sessions", Map.of("outcome", "expired"));
            return ServerResponse.status(HttpStatus.GONE)
                    .body(new McpError("Session expired"));
        }
        RequestContext requestContext = McpRequestContextHolder.current();
        Principal principal = authenticate(requestContext);
        if (principal == null) {
            return unauthorized();
        }
        if (!PrincipalFingerprint.matches(binding.principalFingerprint(),
                PrincipalFingerprint.digest(principal))) {
            telemetry.increment("gateway.mcp.sessions",
                    Map.of("outcome", "principal_mismatch"));
            return ServerResponse.status(HttpStatus.FORBIDDEN).build();
        }

        binding.touch();
        try {
            String body = request.body(String.class);
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(
                    objectMapper, body);
            binding.session().handle(message)
                    .contextWrite(context -> McpRequestContextHolder.bindAuthenticated(
                            context, requestContext, principal))
                    .block();
            return ServerResponse.ok().build();
        } catch (IllegalArgumentException | java.io.IOException | ServletException e) {
            return ServerResponse.badRequest().body(new McpError("Invalid message format"));
        } catch (RuntimeException e) {
            return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new McpError("MCP message handling failed"));
        }
    }

    private Principal authenticate(RequestContext requestContext) {
        try {
            return authenticationPort.authenticate(requestContext);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ServerResponse unauthorized() {
        return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate", "Bearer")
                .build();
    }

    private void evictExpiredSessions() {
        for (Map.Entry<String, SessionBinding> entry : sessions.entrySet()) {
            if (entry.getValue().isExpired(idleTimeout)) {
                removeAndClose(entry.getKey());
                telemetry.increment("gateway.mcp.sessions", Map.of("outcome", "expired"));
            }
        }
    }

    private void removeAndClose(String sessionId) {
        SessionBinding binding = removeSession(sessionId);
        if (binding != null) {
            binding.session().closeGracefully().subscribe();
        }
    }

    private SessionBinding removeSession(String sessionId) {
        SessionBinding removed = sessions.remove(sessionId);
        if (removed != null) {
            sessionSlots.release();
            recordSessionCount();
            telemetry.increment("gateway.mcp.sessions", Map.of("outcome", "closed"));
        }
        return removed;
    }

    private void removeOrReleaseUnpublished(String sessionId) {
        if (removeSession(sessionId) == null) {
            sessionSlots.release();
            recordSessionCount();
        }
    }

    private void recordSessionCount() {
        telemetry.recordValue("gateway.mcp.sessions.active", sessions.size(),
                Map.of("resource", "sse"));
    }

    private static String requirePath(String value, String name) {
        if (value == null || value.isBlank() || value.charAt(0) != '/') {
            throw new IllegalArgumentException(name + " must be an absolute path");
        }
        return value;
    }

    private static final class SessionBinding {
        private final McpServerSession session;
        private final String principalFingerprint;
        private final AtomicLong lastAccessMillis;

        private SessionBinding(McpServerSession session, String principalFingerprint,
                               long lastAccessMillis) {
            this.session = session;
            this.principalFingerprint = principalFingerprint;
            this.lastAccessMillis = new AtomicLong(lastAccessMillis);
        }

        private McpServerSession session() {
            return session;
        }

        private String principalFingerprint() {
            return principalFingerprint;
        }

        private void touch() {
            lastAccessMillis.set(System.currentTimeMillis());
        }

        private boolean isExpired(Duration timeout) {
            return System.currentTimeMillis() - lastAccessMillis.get() > timeout.toMillis();
        }
    }

    private final class SessionTransport implements McpServerTransport {
        private final String sessionId;
        private final ServerResponse.SseBuilder sse;

        private SessionTransport(String sessionId, ServerResponse.SseBuilder sse) {
            this.sessionId = sessionId;
            this.sse = sse;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromRunnable(() -> {
                try {
                    sse.id(sessionId).event(MESSAGE_EVENT_TYPE)
                            .data(objectMapper.writeValueAsString(message));
                } catch (Exception e) {
                    sse.error(e);
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object value, TypeReference<T> typeRef) {
            return objectMapper.convertValue(value, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(sse::complete);
        }

        @Override
        public void close() {
            sse.complete();
        }
    }
}
