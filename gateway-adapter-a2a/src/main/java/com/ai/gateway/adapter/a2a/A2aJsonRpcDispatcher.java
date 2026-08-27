package com.ai.gateway.adapter.a2a;

import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.RequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.a2a.spec.GetAuthenticatedExtendedCardRequest;
import io.a2a.spec.GetAuthenticatedExtendedCardResponse;
import io.a2a.spec.InvalidParamsError;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.JSONParseError;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.Task;

import java.util.Map;
import java.util.Objects;

/**
 * A2A 的 JSON-RPC 分发器：把一段字节流确定性地映射到 {@link A2aServerTransportAdapter} 的三个端点。
 *
 * <p>本类是<b>协议边界</b>，不是传输边界。它持有 A2A 的全部线格知识——方法名、
 * JSON-RPC 信封形态、错误码、{@link A2aTaskContext} 的载荷来源——而把「HTTP 怎么收发」
 * 留给 Web 层。这样切分的直接收益是：网关将来若在 gRPC 或 HTTP+JSON 承载面上同样暴露 A2A，
 * 协议语义只有这一份实现，不会出现两个承载面对同一条报文给出不同判定。</p>
 *
 * <p><b>出参是已序列化的 JSON 文本，而不是对象。</b>这一点看起来多余，实则是必需的：
 * A2A 的线格必须由本模块唯一决定，若交给 Web 层的共享 {@code ObjectMapper} 序列化，
 * 任何一次全局 {@code spring.jackson.*} 调整都会静默改变对外协议的字段形态，
 * 而这种偏移在网关自身的测试里完全看不出来——只有对端能发现。</p>
 *
 * <p><b>不做任何安全判定。</b>认证、限流、注入检测、委托深度全部由
 * {@link A2aPolicyEnforcementFilter} 在 {@link A2aServerTransportAdapter} 内部完成。
 * 本类唯一涉及安全的动作是「把对端载荷解析失败一律收敛成 {@code InvalidParamsError}」：
 * 让 SDK 的断言异常穿透出去，会把一段对端可控的载荷变成 HTTP 500，
 * 那既泄露实现细节，也把可预期的拒绝变成了可观测的异常。</p>
 *
 * <p>本类不可变且线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class A2aJsonRpcDispatcher {

    /** {@code Message.metadata} 中承载委托链标识的键。 */
    public static final String METADATA_ROOT_REQUEST_ID = "a2a.rootRequestId";

    /** {@code Message.metadata} 中承载已经历委托跳数的键。 */
    public static final String METADATA_DELEGATION_DEPTH = "a2a.delegationDepth";

    /** JSON-RPC 版本号；A2A 只使用 2.0。 */
    private static final String JSONRPC_VERSION = "2.0";

    /**
     * 序列化自身失败时的兜底响应。
     *
     * <p>手写而非再走一次序列化：兜底路径若还依赖刚刚失败的那个组件，就不成为兜底。</p>
     */
    private static final String INTERNAL_ERROR_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";

    /**
     * 内部错误对外的固定措辞。
     *
     * <p>刻意是一个常量而不是异常自带的 {@code getMessage()}：把内部异常文本透出去，
     * 等于让对端可以用畸形载荷去枚举网关内部实现——这与 LLM/下游错误文本不得原样外传是同一条约束。</p>
     */
    private static final String INTERNAL_ERROR_MESSAGE = "Internal error";

    private final A2aServerTransportAdapter transportAdapter;
    private final ObjectMapper objectMapper;

    /**
     * 使用本模块自有的 {@link ObjectMapper} 构造分发器。
     *
     * @param transportAdapter 入站传输适配器，不能为 {@code null}
     */
    public A2aJsonRpcDispatcher(A2aServerTransportAdapter transportAdapter) {
        this(transportAdapter, defaultObjectMapper());
    }

    /**
     * @param transportAdapter 入站传输适配器，不能为 {@code null}
     * @param objectMapper     协议专用序列化器，不能为 {@code null}
     */
    public A2aJsonRpcDispatcher(A2aServerTransportAdapter transportAdapter,
                                ObjectMapper objectMapper) {
        this.transportAdapter = Objects.requireNonNull(
                transportAdapter, "transportAdapter must not be null");
        this.objectMapper = Objects.requireNonNull(
                objectMapper, "objectMapper must not be null");
    }

    /**
     * 构造与 A2A SDK 等价的序列化器。
     *
     * <p>只装 {@code JavaTimeModule}：SDK 的记录全部靠自身注解描述线格，
     * 额外的全局配置只会让本网关与其它 A2A 实现产生差异。</p>
     *
     * @return 协议专用序列化器
     */
    public static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * 返回身份无关的公开卡（{@code GET /.well-known/agent-card.json}）。
     *
     * <p>公开卡不是 JSON-RPC 调用，因此响应体是裸的 {@code AgentCard} 而非信封。</p>
     *
     * @return 结果；命中独立配额上限时为 {@link Status#RATE_LIMITED} 且无响应体
     */
    public Result publicCard() {
        A2aServerTransportAdapter.CardResult card = transportAdapter.publicCard();
        if (!card.admitted()) {
            return new Result(Status.RATE_LIMITED, null);
        }
        return new Result(Status.OK, write(card.card()));
    }

    /**
     * 分发一次 JSON-RPC 调用。
     *
     * @param context 入站请求上下文，允许为 {@code null}
     * @param body    请求原文，允许为 {@code null}
     * @return 结果；恒不为 {@code null}
     */
    public Result dispatch(RequestContext context, String body) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            return jsonRpcError(null, new JSONParseError());
        }
        if (envelope == null || !envelope.isObject()) {
            return jsonRpcError(null, new InvalidRequestError("request must be a JSON object"));
        }
        Object id;
        try {
            id = requestId(envelope.get("id"));
        } catch (IllegalArgumentException e) {
            return jsonRpcError(null, new InvalidRequestError("id must be a string or an integer"));
        }
        JsonNode version = envelope.get("jsonrpc");
        if (version == null || !JSONRPC_VERSION.equals(version.asText())) {
            return jsonRpcError(id, new InvalidRequestError("jsonrpc must be \"2.0\""));
        }
        JsonNode method = envelope.get("method");
        if (method == null || !method.isTextual()) {
            return jsonRpcError(id, new InvalidRequestError("method must be a string"));
        }
        return switch (method.asText()) {
            case SendMessageRequest.METHOD -> sendMessage(context, id, envelope.get("params"));
            case GetAuthenticatedExtendedCardRequest.METHOD -> extendedCard(context, id);
            // 未实现的方法（message/stream、tasks/get、tasks/cancel）走标准的方法未找到，
            // 不做「近似映射」：把流式请求当成一次性请求处理，会让对端以为拿到了流。
            default -> jsonRpcError(id, new MethodNotFoundError());
        };
    }

    /**
     * 处理 {@code message/send}。
     *
     * <p>返回的 HTTP 语义恒为成功：受理与拒绝的差异表达在 {@link Task} 的状态里，
     * 而不是表达在传输层状态码上。这是 A2A 的既有约定，同时也保证了
     * 「拒绝原因对端不可区分」这条约束不会被状态码泄露。</p>
     */
    private Result sendMessage(RequestContext context, Object id, JsonNode params) {
        Message message;
        A2aTaskContext taskContext;
        try {
            MessageSendParams sendParams = params == null || params.isNull()
                    ? null : objectMapper.treeToValue(params, MessageSendParams.class);
            if (sendParams == null) {
                return jsonRpcError(id, new InvalidParamsError("params.message is required"));
            }
            message = sendParams.message();
            taskContext = taskContext(message);
        } catch (JsonProcessingException | RuntimeException e) {
            // SDK 的紧凑构造器与断言用未检异常表达「这份载荷不合法」，
            // 与 Jackson 的解析失败是同一件事，因此收敛到同一个错误码。
            return jsonRpcError(id, new InvalidParamsError());
        }
        Task task;
        try {
            task = transportAdapter.handleTask(context, taskContext, message);
        } catch (RuntimeException e) {
            // 执行链抛出未预期异常时不返回任何业务数据：终态审计尚未确定是否落库，
            // 此时回一个内部错误比回一个可能没有留痕的结果更符合失效关闭。
            return jsonRpcError(id, new io.a2a.spec.InternalError(INTERNAL_ERROR_MESSAGE));
        }
        return new Result(Status.OK, write(new SendMessageResponse(id, task)));
    }

    /**
     * 处理 {@code agent/getAuthenticatedExtendedCard}。
     *
     * <p>未认证返回 HTTP 401 而不是 JSON-RPC 错误对象：A2A 把凭据缺失定义为传输层结果，
     * 且 JSON-RPC 的保留错误码里没有语义匹配的一项——自造一个码会与 SDK 后续版本抢占号段。
     * 限流同理走 429。两者都不带响应体，对端从状态码即可得到全部可行动信息。</p>
     */
    private Result extendedCard(RequestContext context, Object id) {
        A2aServerTransportAdapter.CardResult card = transportAdapter.extendedCard(context);
        return switch (card.outcome()) {
            case ADMITTED -> new Result(Status.OK,
                    write(new GetAuthenticatedExtendedCardResponse(id, card.card())));
            case AUTH_REQUIRED -> new Result(Status.UNAUTHORIZED, null);
            case REJECTED -> new Result(Status.RATE_LIMITED, null);
        };
    }

    /**
     * 从入站消息派生任务上下文。
     *
     * <p>三个标识的来源刻意都取自消息本身而不是 HTTP 头：A2A 允许多种承载面，
     * 一旦把上下文绑到 HTTP 头上，换承载面就会丢失委托链信息。</p>
     *
     * <p><b>{@code delegationDepth} 由对端自报，这是协议的固有局限而不是本实现的疏漏。</b>
     * 因此它不是唯一的环路防线：未注册 peer 恒为只读、每个受信档案各有独立的深度上限、
     * 入站另有独立限流。少报深度只能绕过深度这一条，绕不过其余几条。</p>
     */
    private static A2aTaskContext taskContext(Message message) {
        String taskId = firstNonBlank(message.getTaskId(), message.getMessageId());
        String contextId = firstNonBlank(message.getContextId(), taskId);
        Map<String, Object> metadata = message.getMetadata() == null
                ? Map.of() : message.getMetadata();
        String rootRequestId = firstNonBlank(
                text(metadata.get(METADATA_ROOT_REQUEST_ID)), taskId);
        return new A2aTaskContext(taskId, contextId, rootRequestId,
                depth(metadata.get(METADATA_DELEGATION_DEPTH)));
    }

    /**
     * 读取已经历的委托跳数。
     *
     * <p>非数值、缺失或负数一律视为 {@code 0}（链首）。取 {@code 0} 而不是取上限，
     * 是因为「畸形元数据」与「链首请求」在协议上无法区分，而按上限处理会让一段随手写坏的
     * 元数据把正常首跳全部拒掉。真正的防线是深度上限本身，见 {@link #taskContext}。</p>
     */
    private static int depth(Object value) {
        if (value instanceof Number number) {
            int depth = number.intValue();
            return Math.max(depth, 0);
        }
        if (value instanceof String text) {
            try {
                return Math.max(Integer.parseInt(text.trim()), 0);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 归一化 JSON-RPC 请求标识。
     *
     * @return 字符串、整数或 {@code null}
     * @throws IllegalArgumentException 标识既非字符串也非可用整数表示的数值
     */
    private static Object requestId(JsonNode id) {
        if (id == null || id.isNull() || id.isMissingNode()) {
            return null;
        }
        if (id.isTextual()) {
            return id.asText();
        }
        if (id.isIntegralNumber() && id.canConvertToInt()) {
            return id.intValue();
        }
        throw new IllegalArgumentException("unsupported JSON-RPC id");
    }

    private Result jsonRpcError(Object id, JSONRPCError error) {
        return new Result(Status.OK, write(new JSONRPCErrorResponse(id, error)));
    }

    /** 序列化响应；失败时退回手写的内部错误信封。 */
    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return INTERNAL_ERROR_BODY;
        }
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string.trim() : null;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    /**
     * 分发结果。
     *
     * @param status 传输层应当采用的结果语义
     * @param body   已序列化的响应体；{@code null} 表示无响应体
     */
    public record Result(Status status, String body) {

        /**
         * 紧凑构造器。
         *
         * @param status 结果语义，不能为 {@code null}
         * @param body   响应体
         */
        public Result {
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /**
     * 传输层可见的结果语义。
     *
     * <p>刻意只有三项：JSON-RPC 层面的一切失败都用 {@link #OK} 加错误信封表达，
     * 只有「凭据缺失」与「触发配额」两件事在 A2A 里被定义为传输层结果。</p>
     */
    public enum Status {

        /** 正常返回 JSON-RPC 响应（可能是错误信封）。 */
        OK,

        /** 缺少可认证凭据。 */
        UNAUTHORIZED,

        /** 触发入站配额上限。 */
        RATE_LIMITED
    }
}
