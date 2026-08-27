package com.ai.gateway.adapter.a2a;

import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.model.AuditPlane;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.DeadlineBudget;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.SystemContext;
import com.ai.gateway.domain.model.TrustTier;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.ManifestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behavioural contract of the outbound A2A adapter (design §3.7 / §5 P2-1).
 *
 * <p>覆盖设计为该阶段点名的四条性质：出站消息只携带结构化参数、远端返回值以中性载荷交给
 * 下游脱敏链路、并发上限以拒绝而非排队生效、以及会话对象每次调用现场构造。</p>
 */
class A2aInvocationAdapterTest {

    private static final URI ENDPOINT = URI.create("https://orders-agent.internal/a2a");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sendsNamedArgumentsWithoutFreeTextAndReturnsTheRemotePayloadUnchanged()
            throws Exception {
        List<String> bodies = new ArrayList<>();
        A2aClientTransport transport = (endpoint, body, timeout) -> {
            assertThat(endpoint).isEqualTo(ENDPOINT);
            bodies.add(body);
            return new A2aClientResponse(200, completedTask(Map.of("status", "PAID")));
        };

        InvocationResult result =
                adapter(transport, A2aTaskAuditRecorder.noop(), 4).invoke(request(320));

        assertThat(result.errorCode()).isNull();
        assertThat(result.protocolStatus()).isEqualTo("COMPLETED");
        // 适配器只解包，不做投影或脱敏：载荷原样交给下游 ResultNormalizer / RedactionService。
        assertThat(result.jsonData()).isEqualTo(Map.of("status", "PAID"));

        JsonNode sent = MAPPER.readTree(bodies.get(0));
        assertThat(sent.findValue("skillId").asText()).isEqualTo("queryOrder");
        assertThat(sent.findValue("arguments").path("orderNo").asText()).isEqualTo("SO-1");
        // 出站消息里不得出现文本 Part：一次已经确定的调用不该退化成一次语义猜测。
        assertThat(bodies.get(0)).doesNotContain("\"text\"");
    }

    @Test
    void buildsAFreshSessionObjectPerInvocation() {
        // SDK 的 Message 带 setTaskId / setContextId，缓存一个实例复用就等于把可变会话状态
        // 暴露给并发；messageId 每次不同是「没有被复用」的可观测证据。
        List<String> messageIds = new ArrayList<>();
        A2aClientTransport transport = (endpoint, body, timeout) -> {
            messageIds.add(MAPPER.readTree(body).findValue("messageId").asText());
            return new A2aClientResponse(200, completedTask(Map.of("ok", "1")));
        };
        A2aInvocationAdapter adapter = adapter(transport, A2aTaskAuditRecorder.noop(), 4);

        adapter.invoke(request(320));
        adapter.invoke(request(320));

        assertThat(messageIds).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void degradesToRateLimitedInsteadOfQueueingWhenTheConcurrencyCapIsReached()
            throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        A2aClientTransport transport = (endpoint, body, timeout) -> {
            peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            inFlight.countDown();
            release.await(5, TimeUnit.SECONDS);
            concurrent.decrementAndGet();
            return new A2aClientResponse(200, completedTask(Map.of("ok", "1")));
        };
        A2aInvocationAdapter adapter = adapter(transport, A2aTaskAuditRecorder.noop(), 1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<InvocationResult> first = pool.submit(() -> adapter.invoke(request(5_000)));
            assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

            InvocationResult second = adapter.invoke(request(5_000));
            release.countDown();

            // 立刻拒绝而不是排队：出站调用已经处在上游舱壁的截止预算里，
            // 在这里排队只会把一个可以立即回答的拒绝拖成一次超时。
            assertThat(second.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(second.metadata()).containsEntry("reason", "CONCURRENCY_LIMIT");
            assertThat(first.get(5, TimeUnit.SECONDS).errorCode()).isNull();
            assertThat(peak).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void withholdsTheDelegationWhenTheAuditSinkIsUnavailable() {
        AtomicInteger delegations = new AtomicInteger();
        A2aClientTransport transport = (endpoint, body, timeout) -> {
            delegations.incrementAndGet();
            return new A2aClientResponse(200, completedTask(Map.of("ok", "1")));
        };
        A2aTaskAuditRecorder failing = entry -> {
            throw new IllegalStateException("audit sink at 10.20.30.40 is down");
        };

        InvocationResult result = adapter(transport, failing, 4).invoke(request(320));

        // 「记不下来就不发」：一次没有痕迹的对外委托在事后无法归因。
        assertThat(delegations).hasValue(0);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.PROTOCOL_ERROR);
        assertThat(result.metadata()).containsEntry("reason", "AUDIT_UNAVAILABLE");
        assertThat(result.errorMessage()).doesNotContain("10.20.30.40");
    }

    @Test
    void recordsTheDelegationOnTheOutboundPlaneWithTheResolvedEndpointDigest() throws Exception {
        List<A2aTaskAuditRecorder.Entry> entries = new ArrayList<>();
        A2aInvocationAdapter adapter = adapter(
                (endpoint, body, timeout) ->
                        new A2aClientResponse(200, completedTask(Map.of("ok", "1"))),
                entries::add, 4);

        adapter.invoke(request(320));

        assertThat(entries).hasSize(1);
        A2aTaskAuditRecorder.Entry entry = entries.get(0);
        assertThat(entry.eventType()).isEqualTo(A2aTaskAuditRecorder.EventType.DELEGATED);
        // 入站与出站分成两个平面取值，正是为了让 subjectDigest 的语义可被区分。
        assertThat(entry.eventType().plane()).isEqualTo(AuditPlane.A2A_OUTBOUND);
        // 摘要取自已解析出的端点而不是引用键：同一个键在不同环境指向不同 Agent。
        assertThat(entry.identity().peerDigest()).isEqualTo(sha256Hex(ENDPOINT.toString()));
        assertThat(entry.identity().trustTier()).isEqualTo(TrustTier.UNTRUSTED);
        assertThat(entry.details()).containsEntry("skillId", "queryOrder");
    }

    @Test
    void mapsJsonRpcContractErrorsToANonRetryableRejectionWithoutRemoteWording() {
        String remote = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32602,"
                + "\"message\":\"invalid params at 10.20.30.40:8080\"}}";

        InvocationResult result = adapter((endpoint, body, timeout) ->
                new A2aClientResponse(200, remote), A2aTaskAuditRecorder.noop(), 4)
                .invoke(request(320));

        assertThat(result.errorCode()).isEqualTo(ErrorCode.PROVIDER_REJECTED);
        assertThat(result.errorMessage()).isEqualTo("Remote agent rejected the delegation");
        assertThat(result.errorMessage()).doesNotContain("10.20.30.40");
        assertThat(result.metadata()).containsEntry("reason", "JSONRPC_-32602");
    }

    @Test
    void mapsANonTerminalRemoteStateToExecutionUnknown() {
        // 请求已经到达远端、副作用可能已经发生；报成失败会诱导上游重试一次可能已完成的写。
        InvocationResult result = adapter((endpoint, body, timeout) ->
                new A2aClientResponse(200, taskWithState("working", "")),
                A2aTaskAuditRecorder.noop(), 4).invoke(request(320));

        assertThat(result.errorCode()).isEqualTo(ErrorCode.EXECUTION_UNKNOWN);
        assertThat(result.metadata()).containsEntry("reason", "NON_TERMINAL_STATE");
    }

    @Test
    void rejectsAnAmbiguousResultInsteadOfPickingOneStructuredPart() {
        // 挑选规则一旦存在，同一份清单就会因远端的排版差异产出不同结果。
        String artifacts = "\"artifacts\":[{\"artifactId\":\"a-1\",\"parts\":["
                + "{\"kind\":\"data\",\"data\":{\"a\":1}},"
                + "{\"kind\":\"data\",\"data\":{\"b\":2}}]}]";

        InvocationResult result = adapter((endpoint, body, timeout) ->
                new A2aClientResponse(200, taskWithState("completed", artifacts)),
                A2aTaskAuditRecorder.noop(), 4).invoke(request(320));

        assertThat(result.errorCode()).isEqualTo(ErrorCode.PROTOCOL_ERROR);
        assertThat(result.metadata()).containsEntry("reason", "RESULT_AMBIGUOUS");
    }

    @Test
    void rejectsAnInlineUrlAsTheAgentReference() {
        // 安全约束而非风格约束：允许清单内联地址等于让能力作者给网关新增出站目标。
        ValidationReport report = adapter((endpoint, body, timeout) -> null,
                A2aTaskAuditRecorder.noop(), 4)
                .validate(binding("https://evil.example.com/a2a"));

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(e -> e.contains("operator-configured key"));
    }

    @Test
    void doesNotDelegateWhenTheDeadlineBudgetIsAlreadyExhausted() {
        AtomicInteger delegations = new AtomicInteger();
        A2aClientTransport transport = (endpoint, body, timeout) -> {
            delegations.incrementAndGet();
            return new A2aClientResponse(200, completedTask(Map.of("ok", "1")));
        };

        InvocationResult result = adapter(transport, A2aTaskAuditRecorder.noop(), 4)
                .invoke(request(0));

        assertThat(delegations).hasValue(0);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.PROVIDER_TIMEOUT);
        assertThat(result.metadata()).containsEntry("reason", "DEADLINE_EXPIRED");
    }

    @Test
    void mapsAnUnroutableAgentReferenceToAProtocolErrorWithoutLeakingTheReason() {
        A2aInvocationAdapter adapter = new A2aInvocationAdapter(
                manifests(binding("orders-agent")),
                agentRef -> {
                    throw new IllegalArgumentException(
                            "A2A agent endpoint is not configured: " + agentRef);
                },
                (endpoint, body, timeout) -> new A2aClientResponse(200, null),
                A2aTaskAuditRecorder.noop(), MAPPER, 4);

        InvocationResult result = adapter.invoke(request(320));

        assertThat(result.errorCode()).isEqualTo(ErrorCode.PROTOCOL_ERROR);
        assertThat(result.metadata()).containsEntry("reason", "ENDPOINT_NOT_CONFIGURED");
    }

    private static A2aInvocationAdapter adapter(A2aClientTransport transport,
                                                A2aTaskAuditRecorder recorder,
                                                int maxConcurrency) {
        return new A2aInvocationAdapter(manifests(binding("orders-agent")),
                agentRef -> ENDPOINT, transport, recorder, MAPPER, maxConcurrency);
    }

    private static ManifestRepository manifests(ProtocolBinding binding) {
        ManifestRepository repository = mock(ManifestRepository.class);
        when(repository.findByIdAndVersion("orders.query", "1.0.0"))
                .thenReturn(Optional.of(manifest(binding)));
        return repository;
    }

    private static InvocationRequest request(long remainingMs) {
        return new InvocationRequest("orders.query", "1.0.0", "digest",
                new DeadlineBudget(5_000, remainingMs), null,
                new SystemContext("trace-1", System.currentTimeMillis() + remainingMs,
                        null, "zh-CN"),
                List.of("SO-1"));
    }

    private static ProtocolBinding binding(String registryRef) {
        return new ProtocolBinding(Protocol.A2A, registryRef, "orders-domain-agent",
                null, null, "queryOrder", List.of("java.lang.String"), null,
                List.of(new ArgumentBinding(0, "orderNo", "java.lang.String",
                        ArgumentSource.MODEL, "/orderNo", null, null, null)),
                Map.of());
    }

    private static CapabilityManifest manifest(ProtocolBinding binding) {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                "orders.query", "1.0.0",
                new CapabilityManifest.Owner("orders", "orders@example.com"),
                List.of("orders"));
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                "query", "query",
                new CapabilityManifest.Examples(List.of(), List.of(), List.of()),
                RiskLevel.READ_ONLY, Map.of(), null, binding,
                mock(com.ai.gateway.domain.model.OutputContract.class),
                mock(com.ai.gateway.domain.model.ResiliencePolicy.class));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }

    private static String completedTask(Map<String, String> data) throws Exception {
        return taskWithState("completed", "\"artifacts\":[{\"artifactId\":\"a-1\",\"parts\":["
                + "{\"kind\":\"data\",\"data\":" + MAPPER.writeValueAsString(data) + "}]}]");
    }

    /** 拼一条 {@code message/send} 的 JSON-RPC 应答；{@code artifacts} 允许为空串。 */
    private static String taskWithState(String state, String artifacts) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"task\","
                + "\"id\":\"t-1\",\"contextId\":\"c-1\","
                + "\"status\":{\"state\":\"" + state + "\"}"
                + (artifacts.isEmpty() ? "" : "," + artifacts)
                + "}}";
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
