package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.a2a.A2aJsonRpcDispatcher;
import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.domain.model.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * A2A 协议的 HTTP 承载面：两个端点，分别对应分级卡片契约与 JSON-RPC 调用入口。
 *
 * <p>本控制器是<b>纯搬运</b>：它把 Servlet 请求翻译成协议分发器所需的三件东西
 * （{@link RequestContext}、请求体原文、无参触发），然后把分发器的结果翻译成 HTTP 语义。
 * 协议语义——JSON-RPC 信封结构、A2A 方法分发、错误码、{@code A2aTaskContext} 推导——
 * 全部在 {@link A2aJsonRpcDispatcher} 里，这里一字不涉。</p>
 *
 * <p><b>端点职责：</b></p>
 * <ul>
 * <li>{@code GET /.well-known/agent-card.json} — 身份无关的公开卡。
 *     匿名可达；命中独立配额上限时 HTTP 429，无响应体。</li>
 * <li>{@code POST /a2a} — JSON-RPC 调用入口，承载 {@code message/send} 与
 *     {@code agent/getAuthenticatedExtendedCard}。凭据缺失时 HTTP 401（附
 *     {@code WWW-Authenticate: Bearer}），触发配额时 HTTP 429；
 *     所有其它结果（含各类 JSON-RPC 错误信封）一律 HTTP 200，
 *     因为 A2A 把错误语义封在 JSON-RPC 信封里而不是传输层状态码上。</li>
 * </ul>
 *
 * <p>响应体是 {@code application/json;charset=UTF-8} 的字符串，
 * 由 {@link A2aJsonRpcDispatcher} 预序列化完成，Spring 的消息转换器不触及 A2A 类型。</p>
 *
 * <p>本控制器不含 {@code @RequestMapping} 类注解：两条路径刻意避开各自的顶层前缀，
 * 因为 {@code /.well-known/} 是 IETF 约定路径，{@code /a2a} 是协议入口，
 * 都不该被 API 版本前缀污染。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 * @see A2aJsonRpcDispatcher
 */
@RestController
public class A2aProtocolController {

    private static final String CONTENT_TYPE_JSON_UTF8 =
            MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8";

    /**
     * HTTP 401 时的 {@code WWW-Authenticate} 响应头值。
     *
     * <p>A2A 的认证方案固定为 Bearer；在传输层 401 上附上此头，
     * 对端才能判断「缺的是 Bearer Token」而不是其它凭据。</p>
     */
    private static final String WWW_AUTHENTICATE_BEARER = "Bearer realm=\"capability-gateway\"";

    private final A2aJsonRpcDispatcher dispatcher;
    private final RequestContextFactory contextFactory;

    /**
     * @param dispatcher     A2A JSON-RPC 分发器，不能为 {@code null}
     * @param contextFactory 请求上下文工厂，不能为 {@code null}
     */
    public A2aProtocolController(A2aJsonRpcDispatcher dispatcher,
                                 RequestContextFactory contextFactory) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.contextFactory = Objects.requireNonNull(contextFactory,
                "contextFactory must not be null");
    }

    /**
     * 公开卡端点：{@code GET /.well-known/agent-card.json}。
     *
     * <p>匿名可达，不经过 Admin 认证过滤器。配额耗尽时 HTTP 429，无响应体。</p>
     *
     * @return 公开卡 JSON 或 429
     */
    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<String> publicCard() {
        A2aJsonRpcDispatcher.Result result = dispatcher.publicCard();
        return toResponse(result);
    }

    /**
     * A2A JSON-RPC 端点：{@code POST /a2a}。
     *
     * <p>所有方法（{@code message/send}、{@code agent/getAuthenticatedExtendedCard}）
     * 统一进这一个路径。分发与路由由 {@link A2aJsonRpcDispatcher} 完成。</p>
     *
     * @param body    请求体原文；{@code null} 时视为空串，分发器会产生解析错误响应
     * @param request Servlet 请求，用于构造 {@link RequestContext}
     * @return JSON-RPC 响应，或 401/429
     */
    @PostMapping(value = "/a2a",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = CONTENT_TYPE_JSON_UTF8)
    public ResponseEntity<String> dispatch(
            @RequestBody(required = false) String body,
            HttpServletRequest request) {
        RequestContext context = contextFactory.from(request);
        A2aJsonRpcDispatcher.Result result = dispatcher.dispatch(context, body);
        return toResponse(result);
    }

    /**
     * 把分发结果映射到 HTTP 响应。
     *
     * <p>映射规则体现的是 A2A 的传输层契约，不是分发器的自由选择：</p>
     * <ul>
     * <li>{@link A2aJsonRpcDispatcher.Status#OK} → HTTP 200 + 响应体</li>
     * <li>{@link A2aJsonRpcDispatcher.Status#UNAUTHORIZED} → HTTP 401 + {@code WWW-Authenticate}</li>
     * <li>{@link A2aJsonRpcDispatcher.Status#RATE_LIMITED} → HTTP 429</li>
     * </ul>
     * <p>401 和 429 都没有响应体：对端从状态码就能知道该做什么，
     * 额外的响应体既无必要，也会引入「传输层已经用状态码说了一件事，
     * 响应体里说了另一件事」的模糊性。</p>
     */
    private static ResponseEntity<String> toResponse(A2aJsonRpcDispatcher.Result result) {
        return switch (result.status()) {
            case OK -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(CONTENT_TYPE_JSON_UTF8))
                    .body(result.body());
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_BEARER)
                    .build();
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .build();
        };
    }
}
