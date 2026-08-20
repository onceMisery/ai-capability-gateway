package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.Principal;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * 将已认证的 Servlet 请求上下文绑定到同步式 MCP 处理器。
 *
 * <p>同时通过 {@link ThreadLocal} 支撑同步处理链路，并通过 Reactor {@link Context}
 * 支撑响应式链路，确保认证主体在跨线程传递时一致可用。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class McpRequestContextHolder {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();
    private static final Object REACTOR_REQUEST_CONTEXT = new Object();
    private static final Object REACTOR_PRINCIPAL = new Object();

    private McpRequestContextHolder() {
    }

    /**
     * 在当前线程设置请求上下文。
     */
    public static void set(RequestContext context) {
        CURRENT.set(context);
    }

    /**
     * 获取当前线程的请求上下文；未设置时返回空的请求上下文。
     */
    public static RequestContext current() {
        RequestContext context = CURRENT.get();
        return context == null ? RequestContext.empty() : context;
    }

    /**
     * 清除当前线程的请求上下文。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 将请求上下文与认证主体写入 Reactor 上下文，供响应式链路读取。
     */
    static Context bindAuthenticated(Context context,
                                     RequestContext requestContext,
                                     Principal principal) {
        return context.put(REACTOR_REQUEST_CONTEXT, requestContext)
                .put(REACTOR_PRINCIPAL, principal);
    }

    /**
     * 从 Reactor 上下文中读取请求上下文，缺省返回空上下文。
     */
    static RequestContext current(ContextView context) {
        return context.getOrDefault(REACTOR_REQUEST_CONTEXT, RequestContext.empty());
    }

    /**
     * 从 Reactor 上下文中读取认证主体，缺省返回 {@code null}。
     */
    static Principal principal(ContextView context) {
        return context.getOrDefault(REACTOR_PRINCIPAL, null);
    }
}
