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

/**
 * 基于 MCP 0.10 WebMVC SSE 的传输提供者，提供经过认证、与主体绑定的会话。
 *
 * <p>每个 SSE 连接都需通过认证，且会话与认证主体的指纹绑定：后续消息若由不同主体携带，将被拒绝，
 * 以防止会话劫持。会话受最大并发数与空闲超时约束，并通过 Semaphore 进行容量控制。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
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

    /**
     * 构造传输提供者并注册 SSE 与消息路由。
     *
     * <p>消息端点与 SSE 端点必须为绝对路径，且最大会话数与空闲超时必须为正。</p>
     *
     * @param objectMapper       JSON 序列化器
     * @param authenticationPort 认证端口
     * @param telemetry         遥测端口
     * @param messageEndpoint   消息接收端点（绝对路径）
     * @param sseEndpoint       SSE 连接端点（绝对路径）
     * @param maxSessions       最大并发会话数（必须为正）
     * @param idleTimeout       会话空闲超时（必须为正）
     */
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

    /**
     * 返回用于挂载到 Spring WebMVC 的路由函数（SSE 与消息端点）。
     */
    public RouterFunction<ServerResponse> getRouterFunction() {
        return routerFunction;
    }

    /**
     * 返回当前活跃会话数。
     */
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

    /**
     * 处理 SSE 连接：认证、容量控制后建立并绑定会话。
     */
    private ServerResponse handleSseConnection(ServerRequest request) {
        // 关闭中直接拒绝新连接
        if (closing) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        Principal principal = authenticate(McpRequestContextHolder.current());
        if (principal == null) {
            return unauthorized();
        }
        // 接纳新连接前先回收过期会话，释放容量
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

    /**
     * 处理客户端消息：定位会话、校验主体一致性后委派给 MCP 会话处理。
     */
    private ServerResponse handleMessage(ServerRequest request) {
        if (closing) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        // 优先取查询参数 sessionId，缺失时回退到 Mcp-Session-Id 请求头
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
        // 主体指纹不匹配则拒绝，防止会话被其他主体劫持
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

    /**
     * 执行认证；认证失败或异常时返回 {@code null}。
     */
    private Principal authenticate(RequestContext requestContext) {
        try {
            return authenticationPort.authenticate(requestContext);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 返回未认证（401）响应，并携带 Bearer 质询头。
     */
    private ServerResponse unauthorized() {
        return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate", "Bearer")
                .build();
    }

    /**
     * 扫描并移除所有已过期会话。
     */
    private void evictExpiredSessions() {
        for (Map.Entry<String, SessionBinding> entry : sessions.entrySet()) {
            if (entry.getValue().isExpired(idleTimeout)) {
                removeAndClose(entry.getKey());
                telemetry.increment("gateway.mcp.sessions", Map.of("outcome", "expired"));
            }
        }
    }

    /**
     * 移除会话并尝试优雅关闭其底层 MCP 会话。
     */
    private void removeAndClose(String sessionId) {
        SessionBinding binding = removeSession(sessionId);
        if (binding != null) {
            binding.session().closeGracefully().subscribe();
        }
    }

    /**
     * 从会话表移除会话并释放一个容量槽位。
     */
    private SessionBinding removeSession(String sessionId) {
        SessionBinding removed = sessions.remove(sessionId);
        if (removed != null) {
            sessionSlots.release();
            recordSessionCount();
            telemetry.increment("gateway.mcp.sessions", Map.of("outcome", "closed"));
        }
        return removed;
    }

    /**
     * 若会话尚未发布到会话表，则仅释放容量槽位（避免重复释放）。
     */
    private void removeOrReleaseUnpublished(String sessionId) {
        if (removeSession(sessionId) == null) {
            sessionSlots.release();
            recordSessionCount();
        }
    }

    /**
     * 记录当前活跃会话数到遥测。
     */
    private void recordSessionCount() {
        telemetry.recordValue("gateway.mcp.sessions.active", sessions.size(),
                Map.of("resource", "sse"));
    }

    /**
     * 校验端点必须为以 {@code /} 开头的绝对路径。
     */
    private static String requirePath(String value, String name) {
        if (value == null || value.isBlank() || value.charAt(0) != '/') {
            throw new IllegalArgumentException(name + " must be an absolute path");
        }
        return value;
    }

    /**
     * 会话绑定：关联 MCP 会话、主体指纹与最近访问时间，用于过期与主体一致性校验。
     */
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

    /**
     * 基于 SSE 构建器的 MCP 传输实现，负责消息发送与连接关闭。
     */
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
