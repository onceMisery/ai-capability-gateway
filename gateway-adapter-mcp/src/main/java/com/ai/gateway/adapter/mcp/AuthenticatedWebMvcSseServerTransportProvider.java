package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.TelemetryPort;
import com.ai.gateway.domain.service.PrincipalFingerprint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import jakarta.servlet.ServletException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 MCP WebMVC SSE 的认证传输提供者。
 *
 * <p>会话是本机资源，受生命周期状态、容量、空闲回收和单会话并发限制。
 * MCP 入口通过注入的认证策略获取主体，后续消息会重新解析并校验主体指纹。</p>
 */
public final class AuthenticatedWebMvcSseServerTransportProvider
        implements McpServerTransportProvider {

    private static final String MESSAGE_EVENT_TYPE = "message";
    private static final String ENDPOINT_EVENT_TYPE = "endpoint";

    private final ObjectMapper objectMapper;
    private final McpRequestAuthenticator requestAuthenticator;
    private final TelemetryPort telemetry;
    private final String messageEndpoint;
    private final String sseEndpoint;
    private final Duration idleTimeout;
    private final Duration callTimeout;
    private final Duration closeTimeout;
    private final String nodeId;
    private final McpRateLimiter rateLimiter;
    private final McpSessionRequestInterceptor requestInterceptor;
    private final RouterFunction<ServerResponse> routerFunction;
    private final ConcurrentHashMap<String, SessionBinding> sessions =
            new ConcurrentHashMap<>();
    private final Semaphore sessionSlots;
    private final ScheduledExecutorService reaper;
    private final Object lifecycleLock = new Object();
    private final AtomicReference<Lifecycle> lifecycle =
            new AtomicReference<>(Lifecycle.RUNNING);
    private volatile McpServerSession.Factory sessionFactory;

    public AuthenticatedWebMvcSseServerTransportProvider(
            ObjectMapper objectMapper,
            AuthenticationPort authenticationPort,
            TelemetryPort telemetry,
            String messageEndpoint,
            String sseEndpoint,
            int maxSessions,
            Duration idleTimeout) {
        this(objectMapper, authenticationPort, telemetry, messageEndpoint, sseEndpoint,
                maxSessions, idleTimeout, Duration.ofSeconds(30),
                Duration.ofSeconds(5), "local", McpRateLimiter.allowAll());
    }

    public AuthenticatedWebMvcSseServerTransportProvider(
            ObjectMapper objectMapper,
            AuthenticationPort authenticationPort,
            TelemetryPort telemetry,
            String messageEndpoint,
            String sseEndpoint,
            int maxSessions,
            Duration idleTimeout,
            Duration callTimeout) {
        this(objectMapper, authenticationPort, telemetry, messageEndpoint, sseEndpoint,
                maxSessions, idleTimeout, callTimeout, Duration.ofSeconds(5),
                "local", McpRateLimiter.allowAll());
    }

    public AuthenticatedWebMvcSseServerTransportProvider(
            ObjectMapper objectMapper,
            AuthenticationPort authenticationPort,
            TelemetryPort telemetry,
            String messageEndpoint,
            String sseEndpoint,
            int maxSessions,
            Duration idleTimeout,
            Duration callTimeout,
            Duration closeTimeout,
            String nodeId,
            McpRateLimiter rateLimiter) {
        this(objectMapper, McpRequestAuthenticator.bearer(authenticationPort), telemetry,
                messageEndpoint, sseEndpoint, maxSessions, idleTimeout, callTimeout,
                closeTimeout, nodeId, rateLimiter);
    }

    public AuthenticatedWebMvcSseServerTransportProvider(
            ObjectMapper objectMapper,
            McpRequestAuthenticator requestAuthenticator,
            TelemetryPort telemetry,
            String messageEndpoint,
            String sseEndpoint,
            int maxSessions,
            Duration idleTimeout,
            Duration callTimeout,
            Duration closeTimeout,
            String nodeId,
            McpRateLimiter rateLimiter) {
        this(objectMapper, requestAuthenticator, telemetry, messageEndpoint, sseEndpoint,
                maxSessions, idleTimeout, callTimeout, closeTimeout, nodeId, rateLimiter,
                McpSessionRequestInterceptor.none());
    }

    /**
     * @param requestInterceptor 会话级请求拦截器；用于服务按会话身份投影的工具面，
     *                           {@link McpSessionRequestInterceptor#none()} 表示不拦截
     */
    public AuthenticatedWebMvcSseServerTransportProvider(
            ObjectMapper objectMapper,
            McpRequestAuthenticator requestAuthenticator,
            TelemetryPort telemetry,
            String messageEndpoint,
            String sseEndpoint,
            int maxSessions,
            Duration idleTimeout,
            Duration callTimeout,
            Duration closeTimeout,
            String nodeId,
            McpRateLimiter rateLimiter,
            McpSessionRequestInterceptor requestInterceptor) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.requestAuthenticator = Objects.requireNonNull(requestAuthenticator);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.requestInterceptor = Objects.requireNonNull(requestInterceptor);
        this.messageEndpoint = requirePath(messageEndpoint, "messageEndpoint");
        this.sseEndpoint = requirePath(sseEndpoint, "sseEndpoint");
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive");
        }
        this.idleTimeout = requirePositive(idleTimeout, "idleTimeout");
        this.callTimeout = requirePositive(callTimeout, "callTimeout");
        this.closeTimeout = requirePositive(closeTimeout, "closeTimeout");
        this.nodeId = requireNodeId(nodeId);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.sessionSlots = new Semaphore(maxSessions);
        this.reaper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "gateway-mcp-session-reaper");
            thread.setDaemon(true);
            return thread;
        });
        this.routerFunction = RouterFunctions.route()
                .GET(this.sseEndpoint, this::handleSseConnection)
                .POST(this.messageEndpoint, this::handleMessage)
                .build();
        long intervalMillis = Math.max(100L,
                Math.min(Math.max(1L, idleTimeout.toMillis() / 2L), 60_000L));
        this.reaper.scheduleAtFixedRate(this::evictExpiredSessions,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
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

    /**
     * 向本节点所有活跃会话推送 {@code notifications/tools/list_changed}。
     *
     * <p>与 {@link #notifyClients} 的区别是显式排除非本节点会话：{@code sessions} 只保存
     * 本实例建立的会话，但会话键携带 {@code nodeId} 前缀，这里再做一次防御性校验，
     * 使「只处理本节点会话」成为可断言的行为而不是隐含前提。</p>
     *
     * <p>单个会话推送失败（连接已断、客户端不支持该通知）只跳过该会话：推送本身不是
     * 权威失效机制，真正的失效判定发生在执行期重新鉴权。</p>
     *
     * @return 收到推送的会话数
     */
    public int notifyToolListChanged() {
        int notified = 0;
        for (Map.Entry<String, SessionBinding> entry : sessions.entrySet()) {
            if (isForeignSession(entry.getKey())) {
                continue;
            }
            entry.getValue().session()
                    .sendNotification(McpToolListChangeBroadcaster.METHOD, Map.of())
                    .onErrorComplete()
                    .subscribe();
            notified++;
        }
        return notified;
    }

    @Override
    public Mono<Void> closeGracefully() {
        List<SessionBinding> closingSessions = new ArrayList<>();
        synchronized (lifecycleLock) {
            if (!lifecycle.compareAndSet(Lifecycle.RUNNING, Lifecycle.DRAINING)) {
                return Mono.empty();
            }
            reaper.shutdownNow();
            for (Map.Entry<String, SessionBinding> entry :
                    List.copyOf(sessions.entrySet())) {
                SessionBinding binding = removeSession(entry.getKey(), entry.getValue());
                if (binding != null) {
                    closingSessions.add(binding);
                }
            }
        }
        return Flux.fromIterable(closingSessions)
                .flatMap(this::closeBinding)
                .doFinally(signal -> lifecycle.set(Lifecycle.CLOSED))
                .then();
    }

    private ServerResponse handleSseConnection(ServerRequest request) {
        if (!isRunning()) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        RequestContext requestContext = McpRequestContextHolder.current();
        Principal principal = authenticate(requestContext);
        if (principal == null) {
            return unauthorized();
        }
        if (!rateLimiter.tryAcquire(McpRateLimiter.SSE)) {
            telemetry.increment("gateway.mcp.sessions",
                    Map.of("outcome", "rate_limited"));
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new McpError("MCP SSE rate limit reached"));
        }
        evictExpiredSessions();
        synchronized (lifecycleLock) {
            if (!isRunning() || !sessionSlots.tryAcquire()) {
                telemetry.increment("gateway.mcp.sessions",
                        Map.of("outcome", "capacity_rejected"));
                return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS).build();
            }
        }
        String sessionId = nodeId + "." + UUID.randomUUID();
        String principalFingerprint = PrincipalFingerprint.digest(principal);
        try {
            return ServerResponse.sse(sse -> {
                try {
                    SessionTransport transport = new SessionTransport(sessionId, sse);
                    McpServerSession session = Objects.requireNonNull(sessionFactory,
                            "MCP session factory is not initialized").create(transport);
                    SessionBinding binding = new SessionBinding(
                            session, transport, principalFingerprint, System.nanoTime());
                    synchronized (lifecycleLock) {
                        if (!isRunning()) {
                            session.closeGracefully().subscribe(
                                    null, error -> telemetry.increment(
                                            "gateway.mcp.sessions",
                                            Map.of("outcome", "close_failed")));
                            removeOrReleaseUnpublished(sessionId);
                            return;
                        }
                        sessions.put(sessionId, binding);
                    }
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
        if (!isRunning()) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        RequestContext requestContext = McpRequestContextHolder.current();
        String sessionId = request.headers().firstHeader("Mcp-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = request.param("sessionId").orElse(null);
        }
        if (sessionId == null || sessionId.isBlank()) {
            return ServerResponse.badRequest().body(new McpError("Session ID missing"));
        }
        SessionBinding binding = sessions.get(sessionId);
        if (binding == null) {
            if (isForeignSession(sessionId)) {
                telemetry.increment("gateway.mcp.sessions",
                        Map.of("outcome", "wrong_node"));
                return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new McpError("MCP_SESSION_WRONG_NODE"));
            }
            return ServerResponse.status(HttpStatus.NOT_FOUND)
                    .body(new McpError("Session not found"));
        }
        if (binding.isExpired(idleTimeout)) {
            if (removeAndClose(sessionId, binding)) {
                telemetry.increment("gateway.mcp.sessions",
                        Map.of("outcome", "expired"));
            }
            return ServerResponse.status(HttpStatus.GONE)
                    .body(new McpError("Session expired"));
        }
        Principal principal = authenticate(requestContext);
        if (principal == null) {
            return unauthorized();
        }
        if (!rateLimiter.tryAcquire(McpRateLimiter.MESSAGE)) {
            telemetry.increment("gateway.mcp.calls",
                    Map.of("outcome", "rate_limited"));
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new McpError("MCP message rate limit reached"));
        }
        if (!PrincipalFingerprint.matches(binding.principalFingerprint(),
                PrincipalFingerprint.digest(principal))) {
            telemetry.increment("gateway.mcp.sessions",
                    Map.of("outcome", "principal_mismatch"));
            return ServerResponse.status(HttpStatus.FORBIDDEN).build();
        }
        if (!binding.tryAcquire()) {
            telemetry.increment("gateway.mcp.calls",
                    Map.of("outcome", "in_flight_rejected"));
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        try {
            String body = request.body(String.class);
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(
                    objectMapper, body);
            long deadlineNanos = deadlineAfter(callTimeout);
            if (interceptRequest(binding, message, requestContext, deadlineNanos)) {
                binding.touch();
                return ServerResponse.ok().build();
            }
            Mono<Void> handling = binding.session().handle(message);
            if (handling == null) {
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new McpError("MCP message handling failed"));
            }
            handling
                    .contextWrite(context -> McpRequestContextHolder.bindAuthenticated(
                            context, requestContext, principal, deadlineNanos))
                    .timeout(callTimeout)
                    .block();
            binding.touch();
            return ServerResponse.ok().build();
        } catch (IllegalArgumentException | IOException | ServletException e) {
            return ServerResponse.badRequest().body(new McpError("Invalid message format"));
        } catch (RuntimeException e) {
            if (Exceptions.unwrap(e) instanceof TimeoutException) {
                telemetry.increment("gateway.mcp.calls",
                        Map.of("outcome", "timeout"));
                return ServerResponse.status(HttpStatus.GATEWAY_TIMEOUT)
                        .body(new McpError("MCP call timeout"));
            }
            return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new McpError("MCP message handling failed"));
        } finally {
            binding.release();
        }
    }

    private static long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        long deadline = now + timeout.toNanos();
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    /**
     * 给拦截器一次作答机会，用于服务按会话身份投影的工具面。
     *
     * <p>仅拦截带 {@code id} 的请求（{@link McpSchema.JSONRPCRequest}）：通知没有响应可回，
     * 拦截它只会让客户端状态机与 SDK 的认知产生分歧。拦截器返回 {@code null} 时原路交还
     * SDK 会话，因此 {@code initialize}、{@code ping} 等方法的行为完全不变。</p>
     *
     * @return 是否已由拦截器作答
     */
    private boolean interceptRequest(SessionBinding binding, McpSchema.JSONRPCMessage message,
                                     RequestContext requestContext, long deadlineNanos) {
        if (!(message instanceof McpSchema.JSONRPCRequest jsonRpcRequest)) {
            return false;
        }
        Object result;
        try {
            result = requestInterceptor.intercept(jsonRpcRequest.method(),
                    jsonRpcRequest.params(), requestContext, deadlineNanos);
        } catch (RuntimeException e) {
            // 拦截器故障不得吞掉请求：交还 SDK 走既有路径，最差也只是回到静态工具面。
            telemetry.increment("gateway.mcp.calls", Map.of("outcome", "intercept_failed"));
            return false;
        }
        if (result == null) {
            return false;
        }
        binding.transport()
                .sendMessage(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
                        jsonRpcRequest.id(), result, null))
                .block(closeTimeout);
        telemetry.increment("gateway.mcp.calls", Map.of("outcome", "intercepted"));
        return true;
    }

    private Principal authenticate(RequestContext requestContext) {
        try {
            return requestAuthenticator.authenticate(requestContext);
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
            SessionBinding binding = entry.getValue();
            if (binding.isExpired(idleTimeout)
                    && removeAndClose(entry.getKey(), binding)) {
                telemetry.increment("gateway.mcp.sessions",
                        Map.of("outcome", "expired"));
            }
        }
    }

    private void removeAndClose(String sessionId) {
        SessionBinding binding = sessions.get(sessionId);
        if (binding != null) {
            removeAndClose(sessionId, binding);
        }
    }

    private boolean removeAndClose(String sessionId, SessionBinding expected) {
        SessionBinding binding = removeSession(sessionId, expected);
        if (binding == null) {
            return false;
        }
        closeBinding(binding).subscribe();
        return true;
    }

    private SessionBinding removeSession(String sessionId) {
        SessionBinding binding = sessions.get(sessionId);
        return binding == null ? null : removeSession(sessionId, binding);
    }

    private SessionBinding removeSession(String sessionId, SessionBinding expected) {
        if (expected == null || !sessions.remove(sessionId, expected)) {
            return null;
        }
        if (expected.claimClose()) {
            sessionSlots.release();
            recordSessionCount();
            telemetry.increment("gateway.mcp.sessions",
                    Map.of("outcome", "closed"));
        }
        return expected;
    }

    private Mono<Void> closeBinding(SessionBinding binding) {
        if (!binding.closeClaimed()) {
            return Mono.empty();
        }
        return binding.session().closeGracefully()
                .timeout(closeTimeout)
                .doOnError(error -> telemetry.increment("gateway.mcp.sessions",
                        Map.of("outcome", "close_failed")))
                .onErrorComplete();
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

    private boolean isRunning() {
        return lifecycle.get() == Lifecycle.RUNNING;
    }

    private static String requirePath(String value, String name) {
        if (value == null || value.isBlank() || value.charAt(0) != '/') {
            throw new IllegalArgumentException(name + " must be an absolute path");
        }
        return value;
    }

    private static String requireNodeId(String value) {
        if (value == null || value.isBlank()
                || value.length() > 64
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "nodeId must contain only letters, digits, dot, underscore or dash");
        }
        return value;
    }

    private boolean isForeignSession(String sessionId) {
        int separator = sessionId == null ? -1 : sessionId.indexOf('.');
        return separator > 0 && !nodeId.equals(sessionId.substring(0, separator));
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private enum Lifecycle {
        RUNNING, DRAINING, CLOSED
    }

    private static final class SessionBinding {
        private final McpServerSession session;
        private final McpServerTransport transport;
        private final String principalFingerprint;
        private final AtomicLong lastAccessNanos;
        private final Semaphore inFlight = new Semaphore(1);
        private final AtomicBoolean closeClaimed = new AtomicBoolean();

        private SessionBinding(McpServerSession session, McpServerTransport transport,
                               String principalFingerprint, long lastAccessNanos) {
            this.session = session;
            this.transport = transport;
            this.principalFingerprint = principalFingerprint;
            this.lastAccessNanos = new AtomicLong(lastAccessNanos);
        }

        private McpServerSession session() {
            return session;
        }

        /**
         * 直接回写会话传输通道。
         *
         * <p>被拦截的请求必须由我们自己作答，而 {@link McpServerSession} 只暴露
         * {@code sendNotification}，无法回送带 {@code id} 的响应——因此这里保留传输引用。</p>
         */
        private McpServerTransport transport() {
            return transport;
        }

        private String principalFingerprint() {
            return principalFingerprint;
        }

        private void touch() {
            lastAccessNanos.set(System.nanoTime());
        }

        private boolean isExpired(Duration timeout) {
            return System.nanoTime() - lastAccessNanos.get() > timeout.toNanos();
        }

        private boolean tryAcquire() {
            return inFlight.tryAcquire();
        }

        private void release() {
            inFlight.release();
        }

        private boolean claimClose() {
            return closeClaimed.compareAndSet(false, true);
        }

        private boolean closeClaimed() {
            return closeClaimed.get();
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
