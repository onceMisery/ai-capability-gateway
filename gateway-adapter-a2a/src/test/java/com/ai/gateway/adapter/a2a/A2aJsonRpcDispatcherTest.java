package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentCardProjection;
import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acceptance tests for the JSON-RPC protocol boundary (design §3.9).
 *
 * <p>The dispatcher is the only place that knows A2A's wire shape, so these tests pin the wire
 * shape itself rather than the collaboration: envelope validation, method routing, how the task
 * context is derived, and — most importantly — that every way a peer can send something wrong
 * ends as a JSON-RPC error envelope rather than as an exception escaping to the servlet
 * container. A peer-controllable HTTP 500 is both an information leak and a predictable refusal
 * dressed up as an incident.</p>
 *
 * <p>The transport adapter is mocked throughout. That is deliberate: the security decisions it
 * makes already have their own suite, and re-asserting them here would create two homes for one
 * property. What is asserted here is that the dispatcher hands the adapter exactly what the
 * peer said and nothing it invented.</p>
 *
 * @author cmiracle@163.com
 */
class A2aJsonRpcDispatcherTest {

    private static final String JSON_PARSE_ERROR = "-32700";
    private static final String INVALID_REQUEST_ERROR = "-32600";
    private static final String METHOD_NOT_FOUND_ERROR = "-32601";
    private static final String INVALID_PARAMS_ERROR = "-32602";
    private static final String INTERNAL_ERROR = "-32603";

    private static final AgentCardProjection PROJECTION = new AgentCardProjection(
            "capability-gateway", "受治理的企业能力执行平面",
            "https://gateway.internal/a2a", "0.1.0", true,
            List.of("text/plain", "application/json"),
            List.of("text/plain", "application/json"), List.of());

    private static final A2aTaskContext TASK_CONTEXT =
            new A2aTaskContext("task-1", "ctx-1", "root-1", 0);

    private final ObjectMapper mapper = A2aJsonRpcDispatcher.defaultObjectMapper();
    private final A2aTaskStateMapper stateMapper = new A2aTaskStateMapper();
    private A2aServerTransportAdapter transportAdapter;
    private A2aJsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        transportAdapter = mock(A2aServerTransportAdapter.class);
        dispatcher = new A2aJsonRpcDispatcher(transportAdapter);
    }

    // ---------------------------------------------------------------- 公开卡

    @Test
    void thePublicCardIsNotAJsonRpcCallSoItsBodyIsABareCard() {
        when(transportAdapter.publicCard()).thenReturn(admittedCard());

        A2aJsonRpcDispatcher.Result result = dispatcher.publicCard();

        // 公开卡走 GET，不存在请求信封，因此也不该有响应信封——包一层会让标准 A2A 客户端读不出来。
        assertThat(result.status()).isEqualTo(A2aJsonRpcDispatcher.Status.OK);
        assertThat(node(result).has("jsonrpc")).isFalse();
        assertThat(node(result).path("name").asText()).isEqualTo("capability-gateway");
    }

    @Test
    void anExhaustedPublicCardQuotaYieldsNoBodyAtAll() {
        when(transportAdapter.publicCard()).thenReturn(
                new A2aServerTransportAdapter.CardResult(
                        A2aPolicyEnforcementFilter.Outcome.REJECTED, null));

        A2aJsonRpcDispatcher.Result result = dispatcher.publicCard();

        // 匿名端点的配额响应必须是空体：任何响应体都会给爬卡者一个可用于区分的信号。
        assertThat(result.status()).isEqualTo(A2aJsonRpcDispatcher.Status.RATE_LIMITED);
        assertThat(result.body()).isNull();
    }

    // ------------------------------------------------------------ 信封与路由

    @Test
    void anUnparseableBodyBecomesAParseErrorInsteadOfAnException() {
        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(), "{\"jsonrpc\"");

        assertThat(result.status()).isEqualTo(A2aJsonRpcDispatcher.Status.OK);
        assertThat(errorCode(result)).isEqualTo(JSON_PARSE_ERROR);
    }

    @Test
    void aJsonArrayIsRejectedBecauseBatchRequestsAreNotSupported() {
        // JSON-RPC 的批量语法本网关不承载；当成单条处理会让对端以为批量被受理了。
        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(), "[]");

        assertThat(errorCode(result)).isEqualTo(INVALID_REQUEST_ERROR);
    }

    @Test
    void aWrongProtocolVersionIsRejectedRatherThanTolerated() {
        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                "{\"jsonrpc\":\"1.0\",\"id\":\"r-1\",\"method\":\"message/send\"}");

        assertThat(errorCode(result)).isEqualTo(INVALID_REQUEST_ERROR);
        assertThat(node(result).path("id").asText()).isEqualTo("r-1");
    }

    @Test
    void aMissingMethodIsRejectedBeforeAnythingElseIsInterpreted() {
        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                "{\"jsonrpc\":\"2.0\",\"id\":1}");

        assertThat(errorCode(result)).isEqualTo(INVALID_REQUEST_ERROR);
        assertThat(node(result).path("id").asInt()).isEqualTo(1);
    }

    @Test
    void anUnimplementedMethodGetsMethodNotFoundRatherThanAnApproximateMapping() {
        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                envelope("message/stream", "{\"message\":{}}"));

        // 把流式请求当一次性请求处理，对端会一直等一个永不到来的流。
        assertThat(errorCode(result)).isEqualTo(METHOD_NOT_FOUND_ERROR);
        verify(transportAdapter, never()).handleTask(any(), any(), any());
    }

    @Test
    void bothStringAndIntegerRequestIdsAreEchoedInTheirOriginalType() {
        when(transportAdapter.handleTask(any(), any(), any()))
                .thenReturn(stateMapper.rejected(TASK_CONTEXT));

        JsonNode textual = node(dispatcher.dispatch(context(),
                envelope("\"r-9\"", "message/send", params(text("查订单")))));
        JsonNode numeric = node(dispatcher.dispatch(context(),
                envelope("7", "message/send", params(text("查订单")))));

        // JSON-RPC 要求标识按原类型回显；把整数回成字符串会让严格客户端对不上请求。
        assertThat(textual.path("id").isTextual()).isTrue();
        assertThat(textual.path("id").asText()).isEqualTo("r-9");
        assertThat(numeric.path("id").isNumber()).isTrue();
        assertThat(numeric.path("id").asInt()).isEqualTo(7);
    }

    @Test
    void anUnsupportedRequestIdShapeIsRejectedWithoutEchoingIt() {
        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                "{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"message/send\"}");

        // 回显一个本网关无法忠实表示的标识，会让对端拿到一个它并没有发出的值。
        assertThat(errorCode(result)).isEqualTo(INVALID_REQUEST_ERROR);
        JsonNode id = node(result).path("id");
        assertThat(id.isNull() || id.isMissingNode()).isTrue();
    }

    // -------------------------------------------------------- message/send

    @Test
    void anAdmittedTaskIsReturnedInsideTheResultMember() {
        when(transportAdapter.handleTask(any(), any(), any()))
                .thenReturn(stateMapper.rejected(TASK_CONTEXT));

        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                envelope("message/send", params(text("查订单"))));

        assertThat(result.status()).isEqualTo(A2aJsonRpcDispatcher.Status.OK);
        // 拒绝也表达在 Task 状态里而不是错误信封里：这是 A2A 的既有约定。
        assertThat(node(result).path("result").path("status").path("state").asText())
                .isEqualTo("rejected");
        assertThat(node(result).has("error")).isFalse();
    }

    @Test
    void missingParamsIsAnInvalidParamsErrorRatherThanANullPointer() {
        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                "{\"jsonrpc\":\"2.0\",\"id\":\"r-1\",\"method\":\"message/send\"}");

        assertThat(errorCode(result)).isEqualTo(INVALID_PARAMS_ERROR);
        verify(transportAdapter, never()).handleTask(any(), any(), any());
    }

    @Test
    void anIllegalMessagePayloadConvergesToInvalidParamsInsteadOfEscapingAsAnAssertion() {
        // SDK 用未检异常表达「这份载荷不合法」；让它穿透出去，对端就能用畸形载荷制造 HTTP 500。
        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                envelope("message/send", "{\"message\":{\"role\":\"user\",\"parts\":[]}}"));

        assertThat(result.status()).isEqualTo(A2aJsonRpcDispatcher.Status.OK);
        assertThat(errorCode(result)).isEqualTo(INVALID_PARAMS_ERROR);
        verify(transportAdapter, never()).handleTask(any(), any(), any());
    }

    @Test
    void anUnexpectedExecutionFailureReturnsNoBusinessDataAtAll() {
        when(transportAdapter.handleTask(any(), any(), any()))
                .thenThrow(new IllegalStateException("audit store unavailable"));

        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                envelope("message/send", params(text("查订单"))));

        // 终态审计是否落库此时不可知，因此回内部错误而不是回一个可能没有留痕的结果。
        assertThat(errorCode(result)).isEqualTo(INTERNAL_ERROR);
        assertThat(node(result).has("result")).isFalse();
    }

    @Test
    void theInternalErrorCarriesAFixedWordingRatherThanTheExceptionMessage() {
        when(transportAdapter.handleTask(any(), any(), any()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://db:5432 refused"));

        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                envelope("message/send", params(text("查订单"))));

        // 异常文本经常带着连接串、类名与栈信息；原样外传等于把内部拓扑交给对端。
        assertThat(node(result).path("error").path("message").asText())
                .isEqualTo("Internal error");
        assertThat(result.body()).doesNotContain("postgresql");
    }

    // ------------------------------------------------------------ 任务上下文

    @Test
    void theTaskContextIsDerivedFromTheMessageNotFromTransportHeaders() {
        Message message = new Message.Builder()
                .role(Message.Role.USER)
                .parts(new TextPart("查订单"))
                .messageId("msg-1")
                .taskId("task-42")
                .contextId("ctx-42")
                .metadata(Map.of(A2aJsonRpcDispatcher.METADATA_ROOT_REQUEST_ID, "root-42",
                        A2aJsonRpcDispatcher.METADATA_DELEGATION_DEPTH, 2))
                .build();

        A2aTaskContext captured = dispatchAndCapture(message);

        // A2A 允许多种承载面；把委托链绑到 HTTP 头上，换一种承载面就会丢失链路信息。
        assertThat(captured.taskId()).isEqualTo("task-42");
        assertThat(captured.contextId()).isEqualTo("ctx-42");
        assertThat(captured.rootRequestId()).isEqualTo("root-42");
        assertThat(captured.delegationDepth()).isEqualTo(2);
    }

    @Test
    void anAbsentTaskIdFallsBackToTheMessageIdSoTheChainStaysCorrelatable() {
        Message message = new Message.Builder()
                .role(Message.Role.USER)
                .parts(new TextPart("查订单"))
                .messageId("msg-7")
                .build();

        A2aTaskContext captured = dispatchAndCapture(message);

        // 首跳通常不带 taskId；没有兜底标识，这条请求在审计里就无法与后续跳关联。
        assertThat(captured.taskId()).isEqualTo("msg-7");
        assertThat(captured.contextId()).isEqualTo("msg-7");
        assertThat(captured.rootRequestId()).isEqualTo("msg-7");
        assertThat(captured.delegationDepth()).isZero();
    }

    @Test
    void aDelegationDepthWrittenAsTextIsStillAccepted() {
        // JSON 元数据是无类型映射，对端把跳数写成字符串是常见且合法的。
        A2aTaskContext captured = dispatchAndCapture(withMetadata(
                A2aJsonRpcDispatcher.METADATA_DELEGATION_DEPTH, " 3 "));

        assertThat(captured.delegationDepth()).isEqualTo(3);
    }

    @Test
    void aMalformedDepthIsTreatedAsTheChainHeadNotAsTheCap() {
        A2aTaskContext captured = dispatchAndCapture(withMetadata(
                A2aJsonRpcDispatcher.METADATA_DELEGATION_DEPTH, "not-a-number"));

        // 按上限处理会让一段随手写坏的元数据把正常首跳全部拒掉；
        // 真正的环路防线是深度上限、未注册 peer 恒只读与独立限流三条叠加，不是这一处归一化。
        assertThat(captured.delegationDepth()).isZero();
    }

    @Test
    void aNegativeDepthIsClampedToTheChainHeadRatherThanFlowingOnAsNegative() {
        A2aTaskContext captured = dispatchAndCapture(withMetadata(
                A2aJsonRpcDispatcher.METADATA_DELEGATION_DEPTH, -5));

        // 负跳数若原样流下去，任何「已用跳数 < 上限」的比较都会凭空多出几跳预算。
        assertThat(captured.delegationDepth()).isZero();
    }

    @Test
    void aBlankRootRequestIdIsReplacedRatherThanPropagatedEmpty() {
        A2aTaskContext captured = dispatchAndCapture(withMetadata(
                A2aJsonRpcDispatcher.METADATA_ROOT_REQUEST_ID, "   "));

        assertThat(captured.rootRequestId()).isNotBlank();
    }

    @Test
    void theRequestContextIsForwardedUntouchedBecauseCredentialsLiveThere() {
        when(transportAdapter.handleTask(any(), any(), any()))
                .thenReturn(stateMapper.rejected(TASK_CONTEXT));
        RequestContext context = context();

        dispatcher.dispatch(context, envelope("message/send", params(text("查订单"))));

        // 分发器不做任何安全判定，但必须把凭据原样带到唯一做判定的地方。
        ArgumentCaptor<RequestContext> captor = ArgumentCaptor.forClass(RequestContext.class);
        verify(transportAdapter).handleTask(captor.capture(), any(), any());
        assertThat(captor.getValue()).isSameAs(context);
    }

    // ------------------------------------------------------------- 扩展卡

    @Test
    void anAdmittedExtendedCardIsWrappedInAJsonRpcEnvelope() {
        when(transportAdapter.extendedCard(any())).thenReturn(admittedCard());

        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                envelope("agent/getAuthenticatedExtendedCard", "null"));

        assertThat(result.status()).isEqualTo(A2aJsonRpcDispatcher.Status.OK);
        assertThat(node(result).path("result").path("name").asText())
                .isEqualTo("capability-gateway");
    }

    @Test
    void missingCredentialsBecomeATransportResultNotAnErrorEnvelope() {
        when(transportAdapter.extendedCard(any())).thenReturn(
                new A2aServerTransportAdapter.CardResult(
                        A2aPolicyEnforcementFilter.Outcome.AUTH_REQUIRED, null));

        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                envelope("agent/getAuthenticatedExtendedCard", "null"));

        // JSON-RPC 的保留码里没有语义匹配的一项；自造一个码会与 SDK 后续版本抢占号段。
        assertThat(result.status()).isEqualTo(A2aJsonRpcDispatcher.Status.UNAUTHORIZED);
        assertThat(result.body()).isNull();
    }

    @Test
    void anExhaustedExtendedCardQuotaAlsoStaysAtTheTransportLayer() {
        when(transportAdapter.extendedCard(any())).thenReturn(
                new A2aServerTransportAdapter.CardResult(
                        A2aPolicyEnforcementFilter.Outcome.REJECTED, null));

        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                envelope("agent/getAuthenticatedExtendedCard", "null"));

        assertThat(result.status()).isEqualTo(A2aJsonRpcDispatcher.Status.RATE_LIMITED);
        assertThat(result.body()).isNull();
    }

    // -------------------------------------------------------------- 序列化

    @Test
    void theProtocolWireShapeIsOwnedHereAndNotByAnyGlobalMapperSetting() {
        // 若响应交给 Web 层的共享 ObjectMapper 序列化，一次全局 spring.jackson.* 调整
        // 就会静默改变对外协议形态，而这种偏移只有对端能发现。
        when(transportAdapter.handleTask(any(), any(), any()))
                .thenReturn(stateMapper.rejected(TASK_CONTEXT));

        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context(),
                envelope("message/send", params(text("查订单"))));

        assertThat(result.body()).isNotNull().startsWith("{");
        assertThat(node(result).path("jsonrpc").asText()).isEqualTo("2.0");
    }

    // --------------------------------------------------------------- 辅助

    private A2aTaskContext dispatchAndCapture(Message message) {
        when(transportAdapter.handleTask(any(), any(), any()))
                .thenReturn(stateMapper.rejected(TASK_CONTEXT));

        dispatcher.dispatch(context(), envelope("message/send", params(message)));

        ArgumentCaptor<A2aTaskContext> captor = ArgumentCaptor.forClass(A2aTaskContext.class);
        verify(transportAdapter).handleTask(any(), captor.capture(), any());
        return captor.getValue();
    }

    private static Message withMetadata(String key, Object value) {
        return new Message.Builder()
                .role(Message.Role.USER)
                .parts(new TextPart("查订单"))
                .messageId("msg-1")
                .metadata(Map.of(key, value))
                .build();
    }

    private static Message text(String value) {
        return message(new TextPart(value));
    }

    private static Message message(Part<?>... parts) {
        return new Message.Builder().role(Message.Role.USER)
                .parts(parts).messageId("msg-1").build();
    }

    private static A2aServerTransportAdapter.CardResult admittedCard() {
        return new A2aServerTransportAdapter.CardResult(
                A2aPolicyEnforcementFilter.Outcome.ADMITTED,
                new AgentCardCodec().encode(PROJECTION));
    }

    private static RequestContext context() {
        return new RequestContext(Map.of("Authorization", "Bearer peer-token"),
                Map.of(), Map.of(), "10.0.0.1");
    }

    /** 把消息序列化成 {@code params} 的 JSON 文本，走的是与对端相同的线格。 */
    private String params(Message message) {
        try {
            return "{\"message\":" + mapper.writeValueAsString(message) + "}";
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String envelope(String method, String params) {
        return envelope("\"r-1\"", method, params);
    }

    private static String envelope(String id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
    }

    private JsonNode node(A2aJsonRpcDispatcher.Result result) {
        try {
            return mapper.readTree(result.body());
        } catch (Exception e) {
            throw new AssertionError("unparseable response body: " + result.body(), e);
        }
    }

    private String errorCode(A2aJsonRpcDispatcher.Result result) {
        return node(result).path("error").path("code").asText();
    }
}
